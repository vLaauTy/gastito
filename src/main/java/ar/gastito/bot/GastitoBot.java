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
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
 * Conexión con Telegram por long polling. Traduce updates a llamadas a {@link ProcesadorMensajes} y sus
 * respuestas a métodos de la API. Con telegrambots.enabled=false (tests) no se crea.
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
                new BotCommand("ayuda", "Cómo cargar gastos"))).build());
    }

    @Override
    public void consume(Update update) {
        if (!filtro.autorizado(update)) {
            log.debug("Update {} ignorado: no es del usuario permitido", update.getUpdateId());
            return;
        }
        try {
            if (update.hasCallbackQuery()) {
                manejarBoton(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                manejarMensaje(update.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("Error procesando el update {}", update.getUpdateId(), e);
            Long chatId = update.hasMessage() ? update.getMessage().getChatId()
                    : update.hasCallbackQuery() ? update.getCallbackQuery().getMessage().getChatId() : null;
            if (chatId != null) {
                enviar(chatId, Respuesta.texto("Uy, algo salió mal procesando eso 😕 Probá de nuevo."));
            }
        }
    }

    private void manejarMensaje(Message mensaje) {
        long chatId = mensaje.getChatId();
        if (!mensaje.hasText()) {
            enviar(chatId, Respuesta.texto("Por ahora solo entiendo mensajes de texto."));
            return;
        }
        if (!mensaje.getText().startsWith("/")) {
            ejecutar(SendChatAction.builder().chatId(chatId).action("typing").build());
        }
        enviar(chatId, procesador.procesarTexto(chatId, mensaje.getText()));
    }

    private void manejarBoton(CallbackQuery boton) {
        ProcesadorMensajes.ResultadoBoton resultado = procesador.procesarBoton(boton.getData());
        MaybeInaccessibleMessage mensaje = boton.getMessage();
        if (resultado.textoMensaje() != null && mensaje != null) {
            // Editar sin replyMarkup saca los botones, así no se pueden volver a tocar.
            ejecutar(EditMessageText.builder()
                    .chatId(mensaje.getChatId())
                    .messageId(mensaje.getMessageId())
                    .text(resultado.textoMensaje())
                    .build());
        }
        AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> respuesta =
                AnswerCallbackQuery.builder().callbackQueryId(boton.getId());
        if (resultado.aviso() != null) {
            respuesta.text(resultado.aviso());
        }
        ejecutar(respuesta.build());
    }

    private void enviar(long chatId, Respuesta respuesta) {
        SendMessage.SendMessageBuilder<?, ?> mensaje = SendMessage.builder().chatId(chatId).text(respuesta.texto());
        if (!respuesta.botones().isEmpty()) {
            InlineKeyboardRow fila = new InlineKeyboardRow();
            for (Respuesta.Boton b : respuesta.botones()) {
                fila.add(InlineKeyboardButton.builder().text(b.texto()).callbackData(b.datos()).build());
            }
            mensaje.replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(fila).build());
        }
        ejecutar(mensaje.build());
    }

    private void ejecutar(org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> metodo) {
        try {
            telegram.execute(metodo);
        } catch (TelegramApiException e) {
            log.warn("Falló {} en Telegram: {}", metodo.getMethod(), sinToken(e.getMessage()));
        }
    }

    /** Las URLs de la API de Telegram llevan el token: nunca tiene que llegar a los logs. */
    private String sinToken(String texto) {
        return texto == null ? null : texto.replace(token, "<token>");
    }
}
