package ar.gastito.juntada;

/** Un cambio que no se puede hacer en una juntada. El mensaje es para el usuario. */
public class JuntadaInvalidaException extends RuntimeException {

    public JuntadaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
