package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El dólar, sin IA: la cotización de referencia dicha de forma obvia ("el dólar tarjeta está a 1500", "dólar 1.500")
 * y si un mensaje habla de dólares ("20 dólares de Steam", "US$ 15 en Spotify"). Un gasto en dólares no lo resuelve
 * el parser simple ni el historial, que lo tomarían como pesos: va a la IA.
 */
public final class ParserDolar {

    private static final Pattern COTIZACION = Pattern.compile(
            "^(?:el\\s+)?dolar(?:\\s+(?:tarjeta|de la tarjeta))?(?:\\s+(?:esta|sale|vale|cotiza|quedo|hoy))*"
                    + "(?:\\s+(?:a|en))?\\s+\\$?\\s*(\\d[\\d.,]*(?:\\s*(?:mil|k))?)$");
    private static final Pattern MENCIONA = Pattern.compile(
            "(?<![a-z])(?:dolar(?:es|itos)?|dolares|usd|us\\$|u\\$s|u\\$d|verdes)(?![a-z])");

    private ParserDolar() {
    }

    /** "El dólar tarjeta está a 1500" → 1500; vacío si no es eso. */
    public static Optional<BigDecimal> cotizacion(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher m = COTIZACION.matcher(Textos.normalizar(texto).replaceAll("[.!]+$", "").strip());
        return m.matches() ? Montos.parsearConPalabras(m.group(1).replace(" ", "")) : Optional.empty();
    }

    /** Si el mensaje habla de dólares. */
    public static boolean mencionaDolares(String texto) {
        return texto != null && MENCIONA.matcher(Textos.normalizar(texto)).find();
    }
}
