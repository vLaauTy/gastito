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

import ar.gastito.viaje.PanelViaje;
import ar.gastito.viaje.ViajeService;
import ar.gastito.viaje.ViajesProperties;

/**
 * El modo viaje, al arrancar y cada 10 minutos: el primer día "Arrancó el modo viaje: Bariloche", el último "Hoy
 * es el último día", y después del viaje el resumen final. Cada uno una sola vez, anotado recién cuando Telegram lo
 * recibe (si la PC estuvo apagada, llega al prenderla).
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ViajesProgramados {

    private static final Logger log = LoggerFactory.getLogger(ViajesProgramados.class);

    private final ViajeService viajes;
    private final ConversacionViajes conversacion;
    private final GastitoBot bot;
    private final ViajesProperties properties;
    private final Clock clock;

    public ViajesProgramados(ViajeService viajes, ConversacionViajes conversacion, GastitoBot bot,
            ViajesProperties properties, Clock clock) {
        this.viajes = viajes;
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
        if (LocalTime.now(clock).getHour() < properties.horaAvisos()) {
            return;
        }
        try {
            for (PanelViaje v : viajes.paraResumenFinal()) {
                if (bot.enviarAlUsuario(List.of(conversacion.resumenFinal(v)))) {
                    viajes.resumenFinalEnviado(v.id());
                    log.info("Resumen final del viaje {}", v.id());
                }
            }
            for (PanelViaje v : viajes.paraAvisarInicio()) {
                if (bot.enviarAlUsuario(List.of(conversacion.inicio(v)))) {
                    viajes.avisadoInicio(v.id());
                    log.info("Arrancó el modo viaje {}", v.id());
                }
            }
            for (PanelViaje v : viajes.paraAvisarFin()) {
                if (bot.enviarAlUsuario(List.of(conversacion.fin(v)))) {
                    viajes.avisadoFin(v.id());
                    log.info("Último día del viaje {}", v.id());
                }
            }
        } catch (RuntimeException e) {
            log.error("Fallaron los avisos del modo viaje", e);
        }
    }
}
