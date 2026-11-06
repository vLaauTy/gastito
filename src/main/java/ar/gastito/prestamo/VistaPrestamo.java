package ar.gastito.prestamo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Un préstamo listo para mostrar (en el bot o en el dashboard), armado dentro de la transacción.
 *
 * @param movimientoId el gasto del que salió, o null si se cargó solo
 * @param billetera    desde dónde salió la plata ("Galicia", "Transferencia"), o null si no se sabe
 * @param cobrado      lo que ya me pagó
 * @param restante     lo que todavía me debe
 */
public record VistaPrestamo(Long id, String persona, String descripcion, BigDecimal monto, int cantidadCuotas,
        BigDecimal valorCuota, YearMonth primerMes, int dia, LocalDate fecha, String billetera, Long movimientoId,
        Long viajeId, EstadoPrestamo estado, BigDecimal cobrado, BigDecimal restante, long cuotasCobradas,
        List<Cuota> cuotas) {

    public record Cuota(Long id, int numero, LocalDate vence, BigDecimal monto, EstadoCuota estado) {
    }

    /** La próxima cuota que me tiene que pagar, o null si ya no me debe nada. */
    public Cuota proxima() {
        return cuotas.stream().filter(c -> c.estado().porCobrar()).findFirst().orElse(null);
    }

    public boolean terminado() {
        return estado == EstadoPrestamo.TERMINADO;
    }

    /** Todas las cuotas valen lo mismo (salvo, quizás, la última). */
    public boolean cuotasIguales() {
        return cuotas.stream().limit(Math.max(1, cuotas.size() - 1)).map(c -> c.monto().stripTrailingZeros())
                .distinct().count() <= 1;
    }
}
