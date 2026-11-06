package ar.gastito.bot;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.gastito.prestamo.PrestamoService;
import ar.gastito.prestamo.PrestamosProperties;

/**
 * El aviso de las cuotas atrasadas de los préstamos, al arrancar y cada 10 minutos: si una cuota pasa unos días de
 * su fecha sin cobrarse, se avisa una vez con 💬 Armar mensaje y ✅ Me pasó la cuota. Se anota recién cuando Telegram
 * lo recibe, así si la PC estaba apagada llega al prenderla.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PrestamosProgramados {

    private static final Logger log = LoggerFactory.getLogger(PrestamosProgramados.class);

    private final PrestamoService prestamos;
    private final ConversacionPrestamos conversacion;
    private final GastitoBot bot;
    private final PrestamosProperties properties;
    private final Clock clock;

    public PrestamosProgramados(PrestamoService prestamos, ConversacionPrestamos conversacion, GastitoBot bot,
            PrestamosProperties properties, Clock clock) {
        this.prestamos = prestamos;
        this.conversacion = conversacion;
        this.bot = bot;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        revisar();
    }

    @Scheduled(cron = "0 */10 * * * *")
    public synchronized void revisar() {
        if (LocalTime.now(clock).getHour() < properties.horaAviso()) {
            return;
        }
        try {
            for (PrestamoService.Atrasada a : prestamos.paraAvisar()) {
                if (bot.enviarAlUsuario(List.of(conversacion.aviso(a)))) {
                    prestamos.avisada(a.prestamo().id(), a.cuota().id());
                    log.info("Aviso de la cuota {} atrasada del préstamo {}", a.cuota().numero(), a.prestamo().id());
                } else {
                    log.warn("No se pudo avisar la cuota atrasada: se reintenta en 10 minutos");
                }
            }
        } catch (RuntimeException e) {
            log.error("Falló el aviso de las cuotas atrasadas", e);
        }
    }
}
