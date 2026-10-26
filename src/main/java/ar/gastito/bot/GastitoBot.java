package ar.gastito.bot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import ar.gastito.config.GastitoProperties;

/**
 * Conexión con Telegram por long polling. Traduce updates a llamadas a {@link ProcesadorMensajes} y ejecuta
 * las {@link Accion}es que devuelve. Con telegrambots.enabled=false (tests) no se crea.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GastitoBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GastitoBot.class);

    private final String token;
    private final TelegramClient telegram;
    private final FiltroUsuario filtro;
    private final ProcesadorMensajes procesador;
    private final long usuario;
    /** Repite la acción "escribiendo…" mientras se transcribe un audio o se lee un ticket. */
    private final ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("gastito-escribiendo").daemon().factory());

    public GastitoBot(GastitoProperties properties, FiltroUsuario filtro, ProcesadorMensajes procesador) {
        this.token = properties.telegram().token();
        this.usuario = properties.telegram().allowedUserId();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Falta TELEGRAM_BOT_TOKEN en el archivo .env");
        }
        this.telegram = new OkHttpTelegramClient(token);
        this.filtro = filtro;
        this.procesador = procesador;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @AfterBotRegistration
    public void alRegistrar() {
        log.info("Bot de Telegram conectado y escuchando mensajes");
        ejecutar(SetMyCommands.builder().commands(List.of(
                new BotCommand("hoy", "Gastos de hoy y el total"),
                new BotCommand("mes", "Total del mes por categoría"),
                new BotCommand("resumen", "Tu semana hasta hoy (/resumen mes: el mes)"),
                new BotCommand("juntadas", "Quién te debe plata de las juntadas"),
                new BotCommand("fijo", "Agregar un gasto fijo: /fijo seguro 32900 tarjeta día 4"),
                new BotCommand("fijos", "Tus gastos fijos y su próximo cobro"),
                new BotCommand("costos", "Lo gastado en IA este mes"),
                new BotCommand("sueldo", "Cargar el sueldo: /sueldo 380.000 galicia"),
                new BotCommand("aguinaldo", "Cargar el aguinaldo"),
                new BotCommand("ingreso", "Cargar otro ingreso: /ingreso 25000 arreglo de PC"),
                new BotCommand("dashboard", "El link del dashboard (se abre en la PC)"),
                new BotCommand("ayuda", "Cómo cargar gastos"),
                new BotCommand("start", "Empezar y mostrar el menú"))).build());
    }

    @Override
    public void consume(Update update) {
        if (!filtro.autorizado(update)) {
            log.debug("Update {} ignorado: no es del usuario permitido", update.getUpdateId());
            return;
        }
        Long chatId = null;
        try {
            if (update.hasCallbackQuery()) {
                CallbackQuery boton = update.getCallbackQuery();
                MaybeInaccessibleMessage tarjeta = boton.getMessage();
                chatId = tarjeta.getChatId();
                ejecutar(chatId, boton.getId(),
                        procesador.procesarBoton(chatId, tarjeta.getMessageId(), boton.getData()));
            } else if (update.hasMessage()) {
                Message mensaje = update.getMessage();
                chatId = mensaje.getChatId();
                if (mensaje.hasText()) {
                    if (!mensaje.getText().startsWith("/")) {
                        ejecutar(SendChatAction.builder().chatId(chatId).action("typing").build());
                    }
                    ejecutar(chatId, null, procesador.procesarTexto(chatId, mensaje.getMessageId(), mensaje.getText()));
                } else if (mensaje.hasVoice() || mensaje.hasAudio()) {
                    AudioRecibido audio = audio(mensaje);
                    long chat = chatId;
                    ejecutar(chatId, null, escribiendoMientras(chatId,
                            () -> procesador.procesarAudio(chat, mensaje.getMessageId(), audio)));
                } else if (mensaje.hasPhoto() || esImagen(mensaje)) {
                    FotoRecibida foto = foto(mensaje);
                    long chat = chatId;
                    ejecutar(chatId, null, escribiendoMientras(chatId,
                            () -> procesador.procesarFoto(chat, mensaje.getMessageId(), foto)));
                } else {
                    ejecutar(chatId, null, List.of(Accion.Enviar.texto(
                            "Por ahora entiendo mensajes de texto, audios y fotos de tickets.")));
                }
            }
        } catch (RuntimeException e) {
            log.error("Error procesando el update {}", update.getUpdateId(), e);
            if (update.hasCallbackQuery()) {
                ejecutar(AnswerCallbackQuery.builder().callbackQueryId(update.getCallbackQuery().getId())
                        .text("Uy, algo salió mal 😕").build());
            } else if (chatId != null) {
                ejecutar(chatId, null, List.of(Accion.Enviar.texto("Uy, algo salió mal procesando eso 😕 Probá de nuevo.")));
            }
        }
    }

    /** Un mensaje de voz o un archivo de audio. Se descarga en memoria, recién cuando hace falta. */
    private AudioRecibido audio(Message mensaje) {
        String fileId;
        Integer duracion;
        String nombre;
        if (mensaje.hasVoice()) {
            fileId = mensaje.getVoice().getFileId();
            duracion = mensaje.getVoice().getDuration();
            nombre = "voz.oga";
        } else {
            fileId = mensaje.getAudio().getFileId();
            duracion = mensaje.getAudio().getDuration();
            nombre = nombreArchivo(mensaje.getAudio().getFileName(), mensaje.getAudio().getMimeType());
        }
        return new AudioRecibido(duracion == null ? 0 : duracion, nombre, () -> descargar(fileId));
    }

    /** Un archivo con tipo imagen (una foto mandada "como archivo", sin que Telegram la comprima). */
    private static boolean esImagen(Message mensaje) {
        return mensaje.hasDocument() && mensaje.getDocument().getMimeType() != null
                && mensaje.getDocument().getMimeType().toLowerCase().startsWith("image/");
    }

    /** De una foto, la de mayor resolución de las que manda Telegram. Se descarga recién cuando hace falta. */
    private FotoRecibida foto(Message mensaje) {
        String fileId;
        Long bytes;
        if (mensaje.hasPhoto()) {
            PhotoSize mayor = mensaje.getPhoto().stream()
                    .max(Comparator.comparingLong(p -> (long) p.getWidth() * p.getHeight()))
                    .orElseThrow();
            fileId = mayor.getFileId();
            bytes = mayor.getFileSize() == null ? null : mayor.getFileSize().longValue();
        } else {
            fileId = mensaje.getDocument().getFileId();
            bytes = mensaje.getDocument().getFileSize();
        }
        return new FotoRecibida(bytes, mensaje.getCaption(), () -> descargar(fileId));
    }

    private byte[] descargar(String fileId) throws IOException {
        try {
            File archivo = telegram.execute(GetFile.builder().fileId(fileId).build());
            try (InputStream in = telegram.downloadFileAsStream(archivo.getFilePath())) {
                return in.readAllBytes();
            }
        } catch (TelegramApiException e) {
            throw new IOException(sinToken(e.getMessage()));
        }
    }

    /** Whisper reconoce el formato por la extensión. */
    static String nombreArchivo(String nombre, String mimeType) {
        if (nombre != null && nombre.contains(".")) {
            return nombre;
        }
        String tipo = mimeType == null ? "" : mimeType.toLowerCase();
        if (tipo.contains("mpeg") || tipo.contains("mp3")) {
            return "audio.mp3";
        }
        if (tipo.contains("mp4") || tipo.contains("m4a") || tipo.contains("aac")) {
            return "audio.m4a";
        }
        if (tipo.contains("wav")) {
            return "audio.wav";
        }
        return "audio.ogg";
    }

    /**
     * Telegram muestra "escribiendo…" unos 5 segundos: mientras se transcribe (puede tardar más) se repite cada 4,
     * así el usuario ve que el bot está trabajando.
     */
    private List<Accion> escribiendoMientras(long chatId, Supplier<List<Accion>> trabajo) {
        ScheduledFuture<?> escribiendo = programador.scheduleAtFixedRate(
                () -> ejecutar(SendChatAction.builder().chatId(chatId).action("typing").build()),
                0, 4, TimeUnit.SECONDS);
        try {
            return trabajo.get();
        } finally {
            escribiendo.cancel(false);
        }
    }

    @Override
    public void destroy() {
        programador.shutdownNow();
    }

    /** @param callbackId si las acciones responden a un botón, Telegram espera una respuesta (con o sin aviso) */
    private void ejecutar(long chatId, String callbackId, List<Accion> acciones) {
        String aviso = null;
        for (Accion accion : acciones) {
            switch (accion) {
                case Accion.Enviar e -> enviar(chatId, e);
                case Accion.Editar e -> ejecutar(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(e.mensajeId())
                        .text(e.html())
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(inline(e.teclado()))
                        .build());
                case Accion.Borrar b -> ejecutar(DeleteMessage.builder().chatId(chatId).messageId(b.mensajeId()).build());
                case Accion.QuitarBotones q -> ejecutar(EditMessageReplyMarkup.builder()
                        .chatId(chatId)
                        .messageId(q.mensajeId())
                        .replyMarkup(inline(Teclado.NINGUNO))
                        .build());
                case Accion.Avisar a -> aviso = a.texto();
            }
        }
        if (callbackId != null) {
            AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> respuesta =
                    AnswerCallbackQuery.builder().callbackQueryId(callbackId);
            if (aviso != null) {
                respuesta.text(aviso);
            }
            ejecutar(respuesta.build());
        }
    }

    /**
     * Le manda mensajes al usuario sin que él haya escrito (los resúmenes automáticos). En un chat privado, el id
     * del chat es el del usuario.
     *
     * @return si Telegram aceptó todos los mensajes
     */
    public boolean enviarAlUsuario(List<Accion.Enviar> mensajes) {
        boolean todos = true;
        for (Accion.Enviar mensaje : mensajes) {
            todos &= enviar(usuario, mensaje);
        }
        return todos;
    }

    /** @return si Telegram lo aceptó */
    private boolean enviar(long chatId, Accion.Enviar e) {
        SendMessage.SendMessageBuilder<?, ?> mensaje = SendMessage.builder()
                .chatId(chatId)
                .text(e.html())
                .parseMode(ParseMode.HTML);
        if (!e.teclado().isVacio()) {
            mensaje.replyMarkup(inline(e.teclado()));
        } else if (e.conMenu()) {
            mensaje.replyMarkup(MenuFijo.teclado());
        }
        try {
            Message enviado = telegram.execute(mensaje.build());
            if (e.tarjetaDeMovimiento() != null) {
                procesador.tarjetaEnviada(chatId, e.tarjetaDeMovimiento(), enviado.getMessageId());
            }
            return true;
        } catch (TelegramApiException ex) {
            log.warn("Falló sendMessage en Telegram: {}", sinToken(ex.getMessage()));
            return false;
        }
    }

    /** Sin botones devuelve un teclado vacío, que al editar saca los botones que había. */
    private static InlineKeyboardMarkup inline(Teclado teclado) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> markup = InlineKeyboardMarkup.builder();
        if (teclado.isVacio()) {
            return markup.keyboard(List.of()).build();
        }
        for (List<Teclado.Boton> fila : teclado.filas()) {
            InlineKeyboardRow row = new InlineKeyboardRow();
            for (Teclado.Boton b : fila) {
                row.add(InlineKeyboardButton.builder().text(b.texto()).callbackData(b.datos()).build());
            }
            markup.keyboardRow(row);
        }
        return markup.build();
    }

    private void ejecutar(BotApiMethod<?> metodo) {
        try {
            telegram.execute(metodo);
        } catch (TelegramApiException e) {
            String mensaje = sinToken(e.getMessage());
            if (mensaje != null && mensaje.contains("message is not modified")) {
                return; // se tocó un botón que deja la tarjeta igual: no es un error
            }
            log.warn("Falló {} en Telegram: {}", metodo.getMethod(), mensaje);
        }
    }

    /** Las URLs de la API de Telegram llevan el token: nunca tiene que llegar a los logs. */
    private String sinToken(String texto) {
        return texto == null ? null : texto.replace(token, "<token>");
    }
}
