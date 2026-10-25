package ar.gastito.interpretacion;

import java.util.regex.Pattern;

/**
 * Las preguntas obvias se reconocen sin llamar a la IA: "¿cuánto gasté en kiosco?", "compará octubre con
 * septiembre". Salvo que el mensaje tenga un monto: "¿cuánto salió la nafta? 9000" es un gasto, y ahí decide la IA
 * (en la misma llamada que interpreta el gasto).
 */
final class ClasificadorIntencion {

    /** Empieza como una pregunta o un pedido de datos (ya sin tildes ni mayúsculas). */
    private static final Pattern COMIENZO_DE_PREGUNTA = Pattern.compile(
            "^(cuanto|cuanta|cuantos|cuantas|cual|cuales|que|en que|donde|cuando|como|compara|comparame|"
                    + "mostrame|decime|listame|lista)\\b");

    /**
     * Un monto: 3 o más cifras ("1500", "1.500", "1.500,50") o "15 mil" / "15k". No cuentan los días ("del 3 al
     * 10") ni los años ("en 2026").
     */
    private static final Pattern MONTO = Pattern.compile(
            "(?<![\\d/])(?:\\d{1,3}(?:\\.\\d{3})+|\\d{3,})(?:,\\d{1,2})?(?![\\d/])|\\d+\\s*(?:mil|k)\\b");
    private static final Pattern ANIO = Pattern.compile("^(?:19|20)\\d{2}$");

    private ClasificadorIntencion() {
    }

    static boolean esPreguntaObvia(String texto) {
        if (texto == null || texto.isBlank() || tieneMonto(texto)) {
            return false;
        }
        String t = Textos.normalizar(texto);
        return texto.contains("¿") || texto.strip().endsWith("?") || COMIENZO_DE_PREGUNTA.matcher(t).find();
    }

    static boolean tieneMonto(String texto) {
        var m = MONTO.matcher(texto.toLowerCase());
        while (m.find()) {
            if (!ANIO.matcher(m.group()).matches()) {
                return true;
            }
        }
        return false;
    }
}
