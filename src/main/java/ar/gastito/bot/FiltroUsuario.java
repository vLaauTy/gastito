package ar.gastito.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import ar.gastito.config.GastitoProperties;

/**
 * Los bots de Telegram son públicos: solo se atiende a TELEGRAM_ALLOWED_USER_ID.
 * Todo lo demás (otros usuarios, canales, updates sin remitente) se ignora en silencio.
 */
@Component
public class FiltroUsuario {

    private static final Logger log = LoggerFactory.getLogger(FiltroUsuario.class);

    private final long usuarioPermitido;

    public FiltroUsuario(GastitoProperties properties) {
        this.usuarioPermitido = properties.telegram().allowedUserId();
        if (usuarioPermitido == 0) {
            log.warn("TELEGRAM_ALLOWED_USER_ID no está configurado: el bot va a ignorar todos los mensajes");
        }
    }

    public boolean autorizado(Update update) {
        if (update == null || usuarioPermitido == 0) {
            return false;
        }
        User remitente = null;
        if (update.hasMessage()) {
            remitente = update.getMessage().getFrom();
        } else if (update.hasCallbackQuery()) {
            remitente = update.getCallbackQuery().getFrom();
        }
        return remitente != null && remitente.getId() != null && remitente.getId() == usuarioPermitido;
    }
}
