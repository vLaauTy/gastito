package ar.gastito.bot;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.gastito.ia.TopeIaService;

/**
 * Avisa por Telegram cuando la IA llega al 80 % y al 100 % del tope mensual (gastito.ia.tope-mensual-usd). Revisa al
 * arrancar y cada minuto. Cada aviso se manda una sola vez por mes: recién se anota cuando Telegram lo recibió, así que
 * si falla se reintenta en la próxima vuelta. No bloquea nada.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AvisosTopeIaProgramados {

    private static final Logger log = LoggerFactory.getLogger(AvisosTopeIaProgramados.class);

    private final TopeIaService tope;
    private final FormatoCostos formato;
    private final GastitoBot bot;

    public AvisosTopeIaProgramados(TopeIaService tope, FormatoCostos formato, GastitoBot bot) {
        this.tope = tope;
        this.formato = formato;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        revisar();
    }

    @Scheduled(cron = "0 * * * * *")
    public synchronized void revisar() {
        try {
            Optional<TopeIaService.Aviso> aviso = tope.pendiente();
            if (aviso.isEmpty()) {
                return;
            }
            if (bot.enviarAlUsuario(List.of(formato.aviso(aviso.get())))) {
                tope.marcarEnviado(aviso.get());
                log.info("Aviso del {}% del tope mensual de la IA", aviso.get().umbral());
            } else {
                log.warn("No se pudo mandar el aviso del tope de la IA: se reintenta en un minuto");
            }
        } catch (RuntimeException e) {
            log.error("Falló la revisión del tope mensual de la IA", e);
        }
    }
}
