package ar.gastito.movimiento;

/**
 * Lo que se pidió no se puede hacer así sobre este movimiento (una juntada, una cuota, el cobro de un fijo, una deuda
 * dada por perdida): el mensaje explica por qué, o qué hacer en cambio, y se puede mostrar tal cual.
 */
public class MovimientoEspecialException extends IllegalStateException {

    public MovimientoEspecialException(String explicacion) {
        super(explicacion);
    }
}
