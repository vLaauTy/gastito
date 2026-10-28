package ar.gastito.bot;

/**
 * Un PDF mandado como archivo, sin depender de la API de Telegram.
 *
 * @param bytes    lo que pesa según Telegram, o null si no lo informa
 * @param texto    lo que el usuario escribió con el archivo, o null
 * @param descarga se llama recién después de revisar el tamaño
 */
public record PdfRecibido(Long bytes, String texto, FotoRecibida.Descarga descarga) {
}
