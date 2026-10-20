package ar.gastito.audio;

/** No se pudo transcribir un audio. El mensaje nunca incluye la URL ni la clave del servidor. */
public class TranscripcionNoDisponibleException extends RuntimeException {

    public enum Motivo {
        /** El servidor está apagado, no responde o tardó más que el timeout. */
        SIN_RESPUESTA,
        /** El servidor rechazó la clave (WHISPER_API_KEY). */
        CLAVE_RECHAZADA,
        /** El servidor respondió con un error o algo que no se pudo leer. */
        ERROR_DEL_SERVIDOR
    }

    private final Motivo motivo;

    public TranscripcionNoDisponibleException(Motivo motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public Motivo getMotivo() {
        return motivo;
    }
}
