package ar.gastito.movimiento;

/** Se intentó confirmar o cancelar un movimiento que ya no está pendiente (por ejemplo, doble clic). */
public class MovimientoNoPendienteException extends RuntimeException {

    private final transient Movimiento movimiento;

    public MovimientoNoPendienteException(Movimiento movimiento) {
        super("El movimiento " + movimiento.getId() + " ya está " + movimiento.getEstado());
        this.movimiento = movimiento;
    }

    public Movimiento getMovimiento() {
        return movimiento;
    }
}
