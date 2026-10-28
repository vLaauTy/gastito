package ar.gastito.movimiento;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Desde cuándo hay datos: el día del primer gasto confirmado. Un período que empieza antes no tiene datos
 * (no es que se gastó $0): no se compara contra él ni se calculan ahorros con él. Si el registro empezó a mitad
 * del período, tampoco cuenta: estaría incompleto y la comparación engañaría.
 */
@Service
public class InicioRegistro {

    private final MovimientoRepository movimientos;

    public InicioRegistro(MovimientoRepository movimientos) {
        this.movimientos = movimientos;
    }

    /** El día del primer gasto confirmado, o vacío si todavía no hay ninguno. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> primerDia() {
        LocalDateTime primero = movimientos.primerFechaConfirmada();
        return Optional.ofNullable(primero).map(LocalDateTime::toLocalDate);
    }

    /** Si hay datos para un período que empieza en {@code desde}: el registro ya había empezado ese día. */
    public boolean hayDatosDesde(LocalDate desde) {
        return hayDatosDesde(primerDia(), desde);
    }

    static boolean hayDatosDesde(Optional<LocalDate> primerDia, LocalDate desde) {
        return primerDia.isPresent() && !primerDia.get().isAfter(desde);
    }
}
