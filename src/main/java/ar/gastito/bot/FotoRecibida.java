package ar.gastito.bot;

import java.io.IOException;

/**
 * Una foto, o una imagen mandada como archivo, sin depender de la API de Telegram.
 *
 * @param bytes    lo que pesa según Telegram, o null si no lo informa
 * @param texto    lo que el usuario escribió con la foto ("efectivo", "con la de Galicia"), o null
 * @param descarga se llama recién después de revisar el tamaño
 */
public record FotoRecibida(Long bytes, String texto, Descarga descarga) {

    @FunctionalInterface
    public interface Descarga {
        byte[] descargar() throws IOException;
    }
}
