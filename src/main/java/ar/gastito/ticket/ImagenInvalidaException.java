package ar.gastito.ticket;

/** La imagen no se puede usar como ticket, antes de mandarla a la IA. */
public class ImagenInvalidaException extends RuntimeException {

    public enum Motivo {
        /** Pesa más que gastito.ticket.tamanio-maximo, o tiene demasiados píxeles. */
        DEMASIADO_GRANDE,
        /** No es una imagen que Java sepa abrir (por ejemplo HEIC o WebP mandada como archivo). */
        FORMATO_NO_SOPORTADO
    }

    private final Motivo motivo;

    public ImagenInvalidaException(Motivo motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public Motivo getMotivo() {
        return motivo;
    }
}
