package ar.gastito.bot;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
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
public class GastitoBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(GastitoBot.class);

    private final String token;
    private final TelegramClient telegram;
    private final FiltroUsuario filtro;
    private final ProcesadorMensajes procesador;

    public GastitoBot(GastitoProperties properties, FiltroUsuario filtro, ProcesadorMensajes procesador) {
        this.token = properties.telegram().token();
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
                new BotCommand("costos", "Lo gastado en IA este mes"),
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
                if (!mensaje.hasText()) {
                    ejecutar(chatId, null, List.of(Accion.Enviar.texto("Por ahora solo entiendo mensajes de texto.")));
                    return;
                }
                if (!mensaje.getText().startsWith("/")) {
                    ejecutar(SendChatAction.builder().chatId(chatId).action("typing").build());
                }
                ejecutar(chatId, null, procesador.procesarTexto(chatId, mensaje.getMessageId(), mensaje.getText()));
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

    private void enviar(long chatId, Accion.Enviar e) {
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
        } catch (TelegramApiException ex) {
            log.warn("Falló sendMessage en Telegram: {}", sinToken(ex.getMessage()));
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
