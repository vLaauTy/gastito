package ar.gastito.movimiento;

/** Se intentó guardar un gasto al que todavía le falta el monto, la categoría o (con crédito) la tarjeta. */
public class MovimientoIncompletoException extends RuntimeException {

    public MovimientoIncompletoException(Long id) {
        super("Al movimiento " + id + " le falta el monto, la categoría o la tarjeta de crédito");
    }
}
