package ar.gastito.recibo;

import java.util.regex.Pattern;

/**
 * Borra de un texto lo que un recibo de sueldo trae y Gastito no guarda: CUIL y CUIT, CBU y número de legajo.
 * Se usa antes de mandar el texto del PDF a la IA y sobre los textos libres que ella devuelve.
 */
final class DatosPersonales {

    /** Un CUIT o CUIL: "20-12345678-9", "27 12345678 4" (los once dígitos juntos, también). */
    private static final Pattern CUIL =
            Pattern.compile("\\b(?:20|23|24|27|30|33|34)[-\\s.]?\\d{8}[-\\s.]?\\d\\b");
    /** Un CBU (22 dígitos, a veces en dos grupos) o una cuenta larga. */
    private static final Pattern CBU = Pattern.compile("\\b\\d{8}[-\\s]?\\d{14}\\b|\\b\\d{22}\\b");
    private static final Pattern LEGAJO =
            Pattern.compile("(?i)(legajo|leg\\.?|n[°ºo.]*\\s*de\\s*legajo)\\s*[:#.]?\\s*\\d+");

    private DatosPersonales() {
    }

    /** El texto sin esos datos, con los espacios ordenados. Null si no queda nada. */
    static String limpiar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = CBU.matcher(texto).replaceAll(" ");
        limpio = CUIL.matcher(limpio).replaceAll(" ");
        limpio = LEGAJO.matcher(limpio).replaceAll(" ");
        limpio = limpio.replaceAll("\\s+", " ").strip().replaceAll("^[\\s\\-:#*.,]+|[\\s\\-:#*,]+$", "");
        return limpio.isEmpty() ? null : limpio;
    }

    /** Lo mismo, pero conservando los saltos de línea: es el texto entero de un PDF. */
    static String limpiarVariasLineas(String texto) {
        StringBuilder sb = new StringBuilder();
        for (String linea : texto.split("\\R")) {
            String sin = CBU.matcher(linea).replaceAll("***");
            sin = CUIL.matcher(sin).replaceAll("***");
            sin = LEGAJO.matcher(sin).replaceAll("legajo ***");
            sb.append(sin.stripTrailing()).append('\n');
        }
        return sb.toString().strip();
    }
}
