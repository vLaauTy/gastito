package ar.gastito.bot;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.VistaResumenTarjeta;

/**
 * Los avisos de la tarjeta, al arrancar y cada 10 minutos: "¿ya sabés el próximo cierre?" (si sigue estimado, desde
 * 2 días después del cierre, una vez por día), "el 11/11 vence la tarjeta" (3 días antes) y, desde el vencimiento,
 * "¿se debitó?" (una vez por día). Cada aviso se anota recién cuando Telegram lo recibe: si la PC estaba apagada,
 * llega al prenderla.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TarjetaProgramada {

    private static final Logger log = LoggerFactory.getLogger(TarjetaProgramada.class);

    private final CicloTarjetaService ciclo;
    private final ConversacionTarjeta conversacion;
    private final GastitoBot bot;

    public TarjetaProgramada(CicloTarjetaService ciclo, ConversacionTarjeta conversacion, GastitoBot bot) {
        this.ciclo = ciclo;
        this.conversacion = conversacion;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        revisar();
    }

    @Scheduled(cron = "0 */10 * * * *")
    public synchronized void revisar() {
        avisar("las fechas del próximo resumen", ciclo::preguntaDeFechas, conversacion::preguntaDeFechas,
                ciclo::preguntoFechas);
        avisar("el vencimiento", ciclo::avisoDeVencimiento, conversacion::avisoDeVencimiento,
                ciclo::avisoVencimientoEnviado);
        avisar("el débito", ciclo::preguntaDeDebito, conversacion::preguntaDeDebito, ciclo::preguntoDebito);
    }

    private void avisar(String que, Supplier<Optional<VistaResumenTarjeta>> pendiente,
            Function<VistaResumenTarjeta, Accion.Enviar> mensaje, Consumer<Long> enviado) {
        try {
            pendiente.get().ifPresent(r -> {
                if (bot.enviarAlUsuario(List.of(mensaje.apply(r)))) {
                    enviado.accept(r.id());
                    log.info("Aviso de la tarjeta: {} (resumen que vence el {})", que, r.vencimiento());
                } else {
                    log.warn("No se pudo avisar {} de la tarjeta: se reintenta en 10 minutos", que);
                }
            });
        } catch (RuntimeException e) {
            log.error("Falló el aviso de {} de la tarjeta", que, e);
        }
    }
}
