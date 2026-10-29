package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.dashboard.ConsultaMovimientos.Especial;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoEspecialException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.TipoMovimiento;

/**
 * Editar y eliminar desde la tabla de movimientos un gasto guardado. Una juntada, una cuota o el pago de un fijo no
 * se borran como un gasto común: el pedido tiene que decir qué se borra ({@link Alcance}); si no coincide, la
 * respuesta explica qué se puede hacer.
 */
@Service
public class EdicionMovimientos {

    /** Qué se borra: un gasto común, la juntada entera, el plan de cuotas desde esa cuota, o el pago del fijo. */
    public enum Alcance {
        GASTO, JUNTADA, CUOTAS, FIJO
    }

    private final MovimientoService movimientos;
    private final JuntadaService juntadas;
    private final PlanCuotasService cuotas;

    public EdicionMovimientos(MovimientoService movimientos, JuntadaService juntadas, PlanCuotasService cuotas) {
        this.movimientos = movimientos;
        this.juntadas = juntadas;
        this.cuotas = cuotas;
    }

    /**
     * @param fecha       el día nuevo; la hora queda la que tenía
     * @param medioPagoId null es "sin especificar"
     * @param cuentaId    null es sin cuenta
     */
    @Transactional
    public ConsultaMovimientos.Fila editar(long id, BigDecimal monto, LocalDate fecha, String descripcion,
            Long categoriaId, Long medioPagoId, Long cuentaId) {
        Movimiento m = gastoGuardado(id);
        Movimiento editado = movimientos.editar(id, monto, descripcion,
                fecha == null ? m.getFechaHora() : fecha.atTime(m.getFechaHora().toLocalTime()), categoriaId,
                medioPagoId, cuentaId);
        return ConsultaMovimientos.fila(editado);
    }

    /** La foto de un ticket queda en disco; los productos se borran con el gasto. */
    @Transactional
    public void borrar(long id, Alcance alcance) {
        Movimiento m = gastoGuardado(id);
        Especial especial = Especial.de(m);
        Alcance corresponde = especial == null ? Alcance.GASTO : switch (especial) {
            case JUNTADA -> Alcance.JUNTADA;
            case CUOTA -> Alcance.CUOTAS;
            case FIJO -> Alcance.FIJO;
        };
        if (alcance != corresponde) {
            throw new MovimientoEspecialException(switch (corresponde) {
                case JUNTADA -> "Es una juntada: se borra entera, con lo que te deben";
                case CUOTAS -> "Es la cuota " + m.getCuotaActual() + " de " + m.getCuotaTotal()
                        + ": se cancela el plan desde esta cuota";
                case FIJO -> "Es el pago de un gasto fijo: si lo borrás, el fijo vuelve a figurar como que falta";
                case GASTO -> "Es un gasto común: se borra solo ese";
            });
        }
        switch (alcance) {
            case JUNTADA -> juntadas.borrarEntera(id);
            case CUOTAS -> cuotas.cancelarDesde(id);
            case GASTO, FIJO -> movimientos.borrar(id);
        }
    }

    private Movimiento gastoGuardado(long id) {
        Movimiento m = movimientos.buscar(id);
        if (m.getTipo() != TipoMovimiento.GASTO || m.getEstado() != EstadoMovimiento.CONFIRMADO) {
            throw new NoSuchElementException("No existe el gasto guardado " + id);
        }
        return m;
    }
}
