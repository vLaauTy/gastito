package ar.gastito.inversion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Cuenta;

/** Las valuaciones que carga el usuario (por el bot o el dashboard), guardadas en valuacion_inversion. */
@Component
public class ValuacionesManuales implements FuenteDeValuaciones {

    private final ValuacionInversionRepository repositorio;

    public ValuacionesManuales(ValuacionInversionRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Valuacion> deLaCuenta(long cuentaId) {
        return repositorio.findByCuentaIdOrderByFechaAscCreadaEnAscIdAsc(cuentaId).stream()
                .map(v -> new Valuacion(v.getId(), cuentaId, v.getPosicion() == null ? null : v.getPosicion().getId(),
                        v.getFecha(), v.getValor(), v.getOrigen(), v.getCreadaEn()))
                .toList();
    }

    /**
     * @param movimientoId en un ajuste, el aporte o retiro que lo movió (si no, null)
     * @param operacionId  en un ajuste, la compra o venta que lo movió (si no, null)
     */
    @Transactional
    ValuacionInversion registrar(Cuenta cuenta, PosicionInversion posicion, LocalDate fecha, BigDecimal valor,
            OrigenValuacion origen, Long movimientoId, Long operacionId, LocalDateTime ahora) {
        return repositorio.save(new ValuacionInversion(cuenta, posicion, fecha, valor, origen, movimientoId,
                operacionId, ahora));
    }

    /** Borra valuaciones ("Deshacer"); las que ya no existen se ignoran. */
    @Transactional
    void borrar(Collection<Long> ids) {
        repositorio.deleteAllById(ids.stream().filter(repositorio::existsById).toList());
    }

    @Transactional(readOnly = true)
    ValuacionInversion buscar(long id) {
        return repositorio.findById(id).orElseThrow(() -> new NoSuchElementException("No existe la valuación " + id));
    }

    /** Los ajustes que movió un aporte o un retiro que se borra. */
    @Transactional
    void borrarAjustesDeMovimiento(long movimientoId) {
        repositorio.deleteAll(repositorio.findByMovimientoId(movimientoId));
    }

    /** Los ajustes que movió una compra o venta que se borra. */
    @Transactional
    void borrarAjustesDeOperacion(long operacionId) {
        repositorio.deleteAll(repositorio.findByOperacionId(operacionId));
    }
}
