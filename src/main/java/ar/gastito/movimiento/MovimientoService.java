package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MovimientoService {

    private final MovimientoRepository movimientos;
    private final Clock clock;

    public MovimientoService(MovimientoRepository movimientos, Clock clock) {
        this.movimientos = movimientos;
        this.clock = clock;
    }

    public record Confirmacion(Movimiento movimiento, BigDecimal totalMesCategoria) {
    }

    public record ResumenDia(List<Movimiento> gastos, BigDecimal total) {
    }

    public record ResumenMes(YearMonth mes, List<TotalPorCategoria> porCategoria, BigDecimal total) {
    }

    @Transactional
    public Movimiento crearPendiente(MovimientoInterpretado interpretado, Origen origen, String textoOriginal) {
        return movimientos.save(Movimiento.pendiente(interpretado, origen, textoOriginal, LocalDateTime.now(clock)));
    }

    /** Confirma el movimiento y devuelve el total del mes (del mes del movimiento) en su categoría. */
    @Transactional
    public Confirmacion confirmar(Long id) {
        Movimiento m = buscarPendiente(id);
        m.confirmar();
        movimientos.flush();
        Periodo mes = Periodo.delMes(YearMonth.from(m.getFechaHora()));
        BigDecimal total = movimientos.totalGastosDeCategoria(m.getCategoria().getId(), mes.desde(), mes.hasta());
        return new Confirmacion(m, total);
    }

    @Transactional
    public Movimiento cancelar(Long id) {
        Movimiento m = buscarPendiente(id);
        m.cancelar();
        return m;
    }

    @Transactional(readOnly = true)
    public Movimiento buscar(Long id) {
        return movimientos.findConDetalleById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
    }

    @Transactional(readOnly = true)
    public ResumenDia hoy() {
        Periodo dia = Periodo.delDia(LocalDate.now(clock));
        return new ResumenDia(movimientos.gastosConfirmados(dia.desde(), dia.hasta()),
                movimientos.totalGastos(dia.desde(), dia.hasta()));
    }

    @Transactional(readOnly = true)
    public ResumenMes mesActual() {
        YearMonth mes = YearMonth.now(clock);
        Periodo periodo = Periodo.delMes(mes);
        return new ResumenMes(mes, movimientos.totalesPorCategoria(periodo.desde(), periodo.hasta()),
                movimientos.totalGastos(periodo.desde(), periodo.hasta()));
    }

    private Movimiento buscarPendiente(Long id) {
        Movimiento m = buscar(id);
        if (!m.isPendiente()) {
            throw new MovimientoNoPendienteException(m);
        }
        return m;
    }
}
