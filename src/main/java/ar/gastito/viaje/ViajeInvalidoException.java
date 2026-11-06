package ar.gastito.viaje;

/** Un viaje o un pendiente que no se puede cargar así; el mensaje se le muestra al usuario. */
public class ViajeInvalidoException extends RuntimeException {

    public ViajeInvalidoException(String mensaje) {
        super(mensaje);
    }
}
