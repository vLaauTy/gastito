package ar.gastito.bot;

import java.io.IOException;

/**
 * Un mensaje de voz o un archivo de audio, sin depender de la API de Telegram.
 *
 * @param nombreArchivo con extensión (voz.oga): Whisper la usa para reconocer el formato
 * @param descarga      se llama recién después de revisar la duración
 */
public record AudioRecibido(int duracionSegundos, String nombreArchivo, Descarga descarga) {

    @FunctionalInterface
    public interface Descarga {
        byte[] descargar() throws IOException;
    }
}
