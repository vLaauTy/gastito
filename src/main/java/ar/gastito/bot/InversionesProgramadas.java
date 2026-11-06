package ar.gastito.bot;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;
import ar.gastito.inversion.InversionService;

/**
 * El recordatorio de las inversiones, al arrancar y cada 10 minutos: "¿Cuánto vale hoy Cocos? (última: $850.000
 * hace 7 días)", con 🟰 Igual que antes y 🔕 No preguntar más. Uno por cuenta, cada los días que tenga configurados
 * (por defecto 7); se anota recién cuando Telegram lo recibe, así si la PC estaba apagada llega al prenderla.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InversionesProgramadas {

    private static final Logger log = LoggerFactory.getLogger(InversionesProgramadas.class);

    private final InversionService inversiones;
    private final ConversacionInversiones conversacion;
    private final GastitoBot bot;
    private final long chatId;

    public InversionesProgramadas(InversionService inversiones, ConversacionInversiones conversacion, GastitoBot bot,
            GastitoProperties properties) {
        this.inversiones = inversiones;
        this.conversacion = conversacion;
        this.bot = bot;
        // El chat privado con el usuario tiene su mismo id: ahí se espera la respuesta.
        this.chatId = properties.telegram().allowedUserId();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        revisar();
    }

    @Scheduled(cron = "0 */10 * * * *")
    public synchronized void revisar() {
        try {
            for (InversionService.Recordatorio r : inversiones.recordatoriosPendientes()) {
                if (bot.enviarAlUsuario(List.of(conversacion.recordatorio(chatId, r)))) {
                    inversiones.recordatorioEnviado(r.cuentaId());
                    log.info("Recordatorio de la inversión {}", r.cuenta());
                } else {
                    conversacion.olvidar(chatId);
                    log.warn("No se pudo preguntar cuánto vale {}: se reintenta en 10 minutos", r.cuenta());
                }
            }
        } catch (RuntimeException e) {
            log.error("Falló el recordatorio de las inversiones", e);
        }
    }
}
