package ar.gastito.inversion;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los nombres de cuentas, posiciones y metas: para comparar sin mayúsculas ni tildes, y los alias de una posición
 * ("CEDEAR SPY" también se nombra "spy").
 */
public final class NombresInversion {

    /** Lo que dice qué clase de inversión es, no cuál: no sirve solo para reconocer una posición. */
    static final Set<String> GENERICAS = Set.of("cedear", "cedears", "accion", "acciones", "fci", "fondo", "fondos",
            "comun", "de", "del", "inversion", "bono", "bonos", "on", "obligacion", "obligaciones", "negociable",
            "negociables", "plazo", "fijo", "un", "una", "el", "la", "los", "las", "en");

    private NombresInversion() {
    }

    /** "  Acción   YPF " → "accion ypf". */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9ñ$%.]+", " ").strip();
    }

    /** El nombre como lo dijo, sin espacios de más ni puntuación alrededor: "un FCI." → "FCI". */
    public static String limpiar(String nombre) {
        String limpio = nombre == null ? "" : nombre.strip().replaceAll("\\s+", " ")
                .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        limpio = limpio.replaceFirst("(?i)^(un|una|el|la|los|las)\\s+", "");
        if (limpio.isEmpty()) {
            return limpio;
        }
        return Character.toUpperCase(limpio.charAt(0)) + limpio.substring(1);
    }

    /**
     * Si un nombre dicho es esa posición: el nombre completo, uno de sus alias, o lo distintivo de los dos coincide
     * ("SPY" y "cedear spy" son "CEDEAR SPY").
     */
    public static boolean mismaPosicion(PosicionInversion p, String nombre) {
        String dicho = normalizar(limpiar(nombre));
        String suyo = normalizar(p.getNombre());
        Set<String> alias = alias(p);
        if (dicho.isEmpty()) {
            return false;
        }
        if (dicho.equals(suyo) || alias.contains(dicho)) {
            return true;
        }
        String distintivo = palabrasClave(nombre);
        return distintivo != null && (distintivo.equals(suyo) || alias.contains(distintivo));
    }

    /** Los alias guardados de una posición, normalizados. */
    static Set<String> alias(PosicionInversion p) {
        return p.getPalabrasClave() == null ? Set.of()
                : Arrays.stream(p.getPalabrasClave().split(",")).map(NombresInversion::normalizar)
                        .filter(a -> !a.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * Los alias de una posición: lo que queda del nombre sin las palabras genéricas ("CEDEAR SPY" → "spy"), o null
     * si no queda nada distinto del nombre.
     */
    public static String palabrasClave(String nombre) {
        String completo = normalizar(limpiar(nombre));
        String distintivo = Arrays.stream(completo.split(" "))
                .filter(p -> !p.isEmpty() && !GENERICAS.contains(p))
                .collect(Collectors.joining(" "));
        return distintivo.isEmpty() || distintivo.equals(completo) ? null : distintivo;
    }
}
