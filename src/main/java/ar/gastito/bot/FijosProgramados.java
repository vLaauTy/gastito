package ar.gastito.bot;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.RecordatorioFijoService;
import ar.gastito.movimiento.Movimiento;

/**
 * El día del cobro de cada gasto fijo, crea el movimiento pendiente y pregunta "Se cobró el seguro: $32.900.
 * ¿Confirmás?". Revisa al arrancar (así pregunta por los que quedaron sin registrar con la PC apagada) y cada 10
 * minutos. Cada cobro se crea una sola vez ({@link GastoFijoService#generarCobros}); si el mensaje no llega, la
 * pregunta vuelve con /fijos.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FijosProgramados {

    private static final Logger log = LoggerFactory.getLogger(FijosProgramados.class);

    private final GastoFijoService fijos;
    private final RecordatorioFijoService recordatorios;
    private final ConversacionFijos conversacion;
    private final GastitoBot bot;

    public FijosProgramados(GastoFijoService fijos, RecordatorioFijoService recordatorios,
            ConversacionFijos conversacion, GastitoBot bot) {
        this.fijos = fijos;
        this.recordatorios = recordatorios;
        this.conversacion = conversacion;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        revisar();
    }

    @Scheduled(cron = "0 */10 * * * *")
    public synchronized void revisar() {
        try {
            List<Movimiento> cobros = fijos.generarCobros();
            if (!cobros.isEmpty()) {
                if (bot.enviarAlUsuario(cobros.stream().map(conversacion::preguntaDeCobro).toList())) {
                    log.info("{} cobro(s) de gastos fijos para confirmar", cobros.size());
                } else {
                    log.warn(
                            "No se pudo preguntar por {} cobro(s) de gastos fijos: quedan pendientes (aparecen en /fijos)",
                            cobros.size());
                }
            }
        } catch (RuntimeException e) {
            log.error("Falló la generación de los cobros de los gastos fijos", e);
        }
        try {
            recordatorios.siCorresponde().ifPresent(vencidos -> {
                if (bot.enviarAlUsuario(List.of(conversacion.recordatorio(vencidos)))) {
                    log.info("Recordatorio de {} fijo(s) vencido(s) sin cargar", vencidos.size());
                } else {
                    log.warn("No se pudo mandar el recordatorio de fijos vencidos");
                }
            });
        } catch (RuntimeException e) {
            log.error("Falló el recordatorio de gastos fijos vencidos", e);
        }
    }
}
