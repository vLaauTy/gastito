package ar.gastito.ia;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.Periodo;

/**
 * Cuándo avisar que la IA se acerca (80 %) o llega (100 %) al tope mensual. No bloquea nada. Cada aviso se manda una
 * sola vez por mes: queda en aviso_tope_ia recién cuando el bot confirma que lo mandó, así que si Telegram falla se
 * vuelve a intentar. Si el gasto salta de un umbral al otro de golpe, se avisa solo el más alto.
 */
@Service
public class TopeIaService {

    /** De mayor a menor: el primero que se alcanzó es el que importa. */
    static final List<Integer> UMBRALES = List.of(100, 80);
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final UsoIaRepository usos;
    private final AvisoTopeIaRepository avisos;
    private final TopeIaProperties tope;
    private final Clock clock;

    public TopeIaService(UsoIaRepository usos, AvisoTopeIaRepository avisos, TopeIaProperties tope, Clock clock) {
        this.usos = usos;
        this.avisos = avisos;
        this.tope = tope;
        this.clock = clock;
    }

    /**
     * @param umbral     80 o 100
     * @param porcentaje lo gastado sobre el tope, entero hacia abajo (81 %)
     */
    public record Aviso(YearMonth mes, int umbral, BigDecimal gastadoUsd, BigDecimal topeUsd, int porcentaje) {
    }

    /** El tope en dólares, o vacío si los avisos están apagados. */
    public Optional<BigDecimal> topeUsd() {
        BigDecimal valor = tope.topeMensualUsd();
        return valor == null || valor.signum() <= 0 ? Optional.empty() : Optional.of(valor);
    }

    /** El porcentaje del tope que lleva un gasto, entero hacia abajo; vacío si no hay tope. */
    public Optional<Integer> porcentajeDelTope(BigDecimal gastadoUsd) {
        return topeUsd().map(t -> gastadoUsd.multiply(CIEN).divide(t, 0, RoundingMode.DOWN).intValue());
    }

    /** El aviso que corresponde mandar ahora, si hay uno que todavía no se mandó este mes. */
    @Transactional(readOnly = true)
    public Optional<Aviso> pendiente() {
        Optional<BigDecimal> topeUsd = topeUsd();
        if (topeUsd.isEmpty()) {
            return Optional.empty();
        }
        YearMonth mes = YearMonth.now(clock);
        Periodo periodo = Periodo.delMes(mes);
        BigDecimal gastado = usos.resumen(periodo.desde(), periodo.hasta()).costoUsd();
        for (int umbral : UMBRALES) {
            BigDecimal limite = topeUsd.get().multiply(BigDecimal.valueOf(umbral)).divide(CIEN);
            if (gastado.compareTo(limite) >= 0) {
                if (avisos.existsByMesAndUmbral(mes.atDay(1), umbral)) {
                    return Optional.empty();
                }
                return Optional.of(new Aviso(mes, umbral, gastado, topeUsd.get(),
                        porcentajeDelTope(gastado).orElse(umbral)));
            }
        }
        return Optional.empty();
    }

    /** Anota que el aviso se mandó (y los de umbrales menores, que ya no hacen falta). */
    @Transactional
    public void marcarEnviado(Aviso aviso) {
        LocalDateTime ahora = LocalDateTime.now(clock);
        for (int umbral : UMBRALES) {
            if (umbral <= aviso.umbral() && !avisos.existsByMesAndUmbral(aviso.mes().atDay(1), umbral)) {
                avisos.save(new AvisoTopeIa(aviso.mes().atDay(1), umbral, ahora));
            }
        }
    }
}
