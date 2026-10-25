package ar.gastito.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.gastito.resumen.ResumenService;

/**
 * Manda los resúmenes automáticos: el semanal el domingo y el mensual el día 1, a la hora configurada. Revisa
 * cada minuto y apenas arranca la aplicación, así un resumen que venció con la PC apagada sale al prenderla. Qué
 * se mandó lo lleva {@link ResumenService}: nunca se repite.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResumenesProgramados {

    private static final Logger log = LoggerFactory.getLogger(ResumenesProgramados.class);

    /** Si Telegram o la base fallan, no se reintenta cada minuto (cada intento le pide el texto a la IA). */
    static final Duration ESPERA_TRAS_ERROR = Duration.ofMinutes(15);

    private final ResumenService resumenes;
    private final FormatoResumen formato;
    private final GastitoBot bot;
    private final Clock clock;
    private LocalDateTime reintentarDesde = LocalDateTime.MIN;

    public ResumenesProgramados(ResumenService resumenes, FormatoResumen formato, GastitoBot bot, Clock clock) {
        this.resumenes = resumenes;
        this.formato = formato;
        this.bot = bot;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        revisar();
    }

    @Scheduled(cron = "0 * * * * *")
    public synchronized void revisar() {
        LocalDateTime ahora = LocalDateTime.now(clock);
        if (ahora.isBefore(reintentarDesde)) {
            return;
        }
        try {
            for (ResumenService.Envio envio : resumenes.pendientes(ahora)) {
                ResumenService.Armado armado = resumenes.armar(envio.periodo(), true);
                Accion.Enviar mensaje = Accion.Enviar.conBotones(
                        formato.mensaje(armado, ResumenService.ofrecidos(envio)), formato.teclado(envio));
                if (!bot.enviarAlUsuario(List.of(mensaje))) {
                    log.warn("No se pudo mandar el resumen {}: se reintenta en {} minutos", envio.periodo(),
                            ESPERA_TRAS_ERROR.toMinutes());
                    reintentarDesde = ahora.plus(ESPERA_TRAS_ERROR);
                    return;
                }
                resumenes.registrarEnvio(envio, armado.resumen());
                log.info("Resumen {} enviado", envio.periodo());
            }
        } catch (RuntimeException e) {
            log.error("Falló el envío de los resúmenes automáticos: se reintenta en {} minutos",
                    ESPERA_TRAS_ERROR.toMinutes(), e);
            reintentarDesde = ahora.plus(ESPERA_TRAS_ERROR);
        }
    }
}
