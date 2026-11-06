package ar.gastito.prestamo;

/** Un préstamo o un cobro que no se puede hacer así; el mensaje se le muestra al usuario. */
public class PrestamoInvalidoException extends RuntimeException {

    public PrestamoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
