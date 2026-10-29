package ar.gastito.fijo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;

/**
 * Tildar un fijo como "Pagado" con el monto aproximado (queda marcado como estimado) y corregirlo después con el
 * real: desde la web, desde el recordatorio de Telegram o diciéndoselo al bot ("de luz pagué 87.500").
 */
@Service
public class PagoFijoService {

    private final GastoFijoRepository fijos;
    private final MovimientoRepository movimientos;
    private final Clock clock;

    public PagoFijoService(GastoFijoRepository fijos, MovimientoRepository movimientos, Clock clock) {
        this.fijos = fijos;
        this.movimientos = movimientos;
        this.clock = clock;
    }

    /**
     * "Pagado": el gasto del mes se guarda confirmado con el monto aproximado del fijo (el esperado o, si es
     * variable, el del cobro anterior), marcado como estimado. Si el cobro automático de ese mes estaba esperando
     * respuesta, se usa ese mismo.
     *
     * @throws IllegalStateException si ese mes ya estaba cargado
     */
    @Transactional
    public Movimiento pagado(Long fijoId, YearMonth mes, Origen origen) {
        GastoFijo fijo = fijo(fijoId);
        List<Movimiento> delPeriodo = movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijoId, mes.atDay(1));
        if (delPeriodo.stream().anyMatch(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO)) {
            throw new IllegalStateException("Ya cargaste " + fijo.getNombre() + " este mes");
        }
        BigDecimal anterior = movimientos.cobrosConfirmados(fijoId, PageRequest.of(0, 12)).stream()
                .filter(c -> c.getFijoPeriodo().isBefore(mes.atDay(1))).findFirst().map(Movimiento::getMonto)
                .orElse(null);
        BigDecimal monto = fijo.montoAproximado(anterior).setScale(2, RoundingMode.HALF_UP);
        Movimiento m = delPeriodo.stream().filter(c -> c.isPendiente() && c.getOrigen() == Origen.AUTOMATICO)
                .findFirst().orElse(null);
        if (m != null) {
            m.cambiarMontoDeCobro(monto);
        } else {
            LocalDate hoy = LocalDate.now(clock);
            LocalDateTime fecha = YearMonth.from(hoy).equals(mes) ? LocalDateTime.now(clock)
                    : fijo.diaDeCobro(mes).atTime(GastoFijoService.HORA_DEL_COBRO);
            m = movimientos.save(Movimiento.cobroManualDeFijo(MovimientoInterpretado.conCategoria(monto,
                    fijo.getNombre(), fijo.getCategoria(), fijo.getMedioPago(), fijo.getCuenta(), fijo.getTarjeta(),
                    fecha), fijoId, mes.atDay(1), origen, null, LocalDateTime.now(clock)));
        }
        m.marcarMontoEstimado();
        m.confirmar();
        return m;
    }

    /**
     * "Corregir monto": el cobro guardado de un fijo pasa a tener el monto real y deja de ser estimado. Es el mismo
     * gasto: no se borra ni se crea otro.
     */
    @Transactional
    public Movimiento corregirMonto(Long movimientoId, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        Movimiento m = movimientos.findById(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + movimientoId));
        m.corregirMontoDeFijo(monto.setScale(2, RoundingMode.HALF_UP));
        if (m.getGastoFijoId() != null) {
            fijos.findById(m.getGastoFijoId()).ifPresent(f -> f.cobrado(m.getMonto()));
        }
        return m;
    }

    /** El cobro de ese mes, si se tildó con el monto estimado y todavía no se corrigió. */
    @Transactional(readOnly = true)
    public Optional<Movimiento> estimadoDelPeriodo(Long fijoId, YearMonth mes) {
        return movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijoId, mes.atDay(1)).stream()
                .filter(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO && m.isMontoEstimado()).findFirst();
    }

    /** Lo que quedó al actualizar un estimado con el monto que dijo el usuario. */
    public record Actualizacion(Movimiento movimiento, BigDecimal anterior) {
    }

    /**
     * "Actualizar el monto": el gasto que se acaba de interpretar ("de luz pagué 87.500") era el real del que se
     * tildó con el estimado. El estimado pasa a tener ese monto y el nuevo se cancela, sin quedar como cobro del
     * fijo (así el mes no cambia de estado en el checklist).
     */
    @Transactional
    public Actualizacion actualizarEstimadoCon(Long pendienteId) {
        Movimiento nuevo = movimientos.findById(pendienteId)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + pendienteId));
        if (!nuevo.isPendiente() || nuevo.getGastoFijoId() == null) {
            throw new IllegalStateException("Ese gasto ya no está pendiente");
        }
        Movimiento estimado = estimadoDelPeriodo(nuevo.getGastoFijoId(), YearMonth.from(nuevo.getFijoPeriodo()))
                .orElseThrow(() -> new IllegalStateException("Ese fijo ya no tiene un monto estimado"));
        BigDecimal anterior = estimado.getMonto();
        corregirMonto(estimado.getId(), nuevo.getMonto());
        nuevo.desvincularFijo();
        nuevo.cancelar();
        return new Actualizacion(estimado, anterior);
    }

    private GastoFijo fijo(Long id) {
        return fijos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el gasto fijo " + id));
    }
}
