package ar.gastito.bot;

import java.util.List;

/** Un mensaje para mandar al usuario, con botones inline opcionales. Independiente de la API de Telegram. */
public record Respuesta(String texto, List<Boton> botones) {

    public record Boton(String texto, String datos) {
    }

    public static Respuesta texto(String texto) {
        return new Respuesta(texto, List.of());
    }
}
