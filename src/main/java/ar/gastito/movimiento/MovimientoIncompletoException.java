package ar.gastito.movimiento;

/** Se intentó guardar un gasto al que todavía le falta el monto o la categoría. */
public class MovimientoIncompletoException extends RuntimeException {

    public MovimientoIncompletoException(Long id) {
        super("Al movimiento " + id + " le falta el monto o la categoría");
    }
}
