package ar.gastito.interpretacion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Forma normalizada de una descripción para compararla con las que ya confirmó el usuario:
 * minúsculas, sin tildes ni puntuación, sin palabras vacías y con las palabras ordenadas.
 * "Pan de pancho y papas" y "papas y pan de pancho" dan "pan pancho papas".
 */
public final class ClaveDescripcion {

    /** Desde este largo de clave se aceptan errores de tipeo. */
    static final int LARGO_MINIMO_PARA_PARECIDAS = 8;
    /** Cuántas letras distintas se toleran entre dos claves parecidas. */
    static final int DISTANCIA_MAXIMA = 2;
    /** Las palabras más cortas tienen que coincidir exacto ("pan" no es "pana"). */
    static final int LARGO_MINIMO_PALABRA_CON_ERROR = 4;

    private static final Set<String> PALABRAS_VACIAS = Set.of(
            "de", "del", "la", "las", "el", "los", "lo", "un", "una", "unos", "unas", "y", "e", "o", "en", "con",
            "para", "por", "al", "a", "mi", "me", "algo", "compre", "pague", "gaste", "cargue", "saque");

    private ClaveDescripcion() {
    }

    public static String de(String descripcion) {
        if (descripcion == null) {
            return "";
        }
        String limpio = Textos.normalizar(descripcion).replaceAll("[^a-z0-9ñ]+", " ").trim();
        if (limpio.isEmpty()) {
            return "";
        }
        return Arrays.stream(limpio.split(" "))
                .filter(p -> !PALABRAS_VACIAS.contains(p))
                .sorted()
                .collect(Collectors.joining(" "));
    }

    /**
     * Misma clave, o casi si las dos son largas: mismas palabras salvo errores de tipeo que suman hasta
     * {@value #DISTANCIA_MAXIMA} letras, y solo en palabras de {@value #LARGO_MINIMO_PALABRA_CON_ERROR} letras o más.
     * Se compara palabra por palabra porque un error de tipeo puede cambiar el orden ("pacho" va antes que "pan").
     */
    public static boolean parecidas(String claveA, String claveB) {
        if (claveA.isEmpty() || claveB.isEmpty()) {
            return false;
        }
        if (claveA.equals(claveB)) {
            return true;
        }
        if (Math.min(claveA.length(), claveB.length()) < LARGO_MINIMO_PARA_PARECIDAS) {
            return false;
        }
        String[] palabrasA = claveA.split(" ");
        List<String> libresB = new ArrayList<>(List.of(claveB.split(" ")));
        if (palabrasA.length != libresB.size()) {
            return false;
        }
        int total = 0;
        for (String palabra : palabrasA) {
            String mejor = null;
            int mejorDistancia = Integer.MAX_VALUE;
            for (String candidata : libresB) {
                int d = distancia(palabra, candidata);
                if (d < mejorDistancia) {
                    mejor = candidata;
                    mejorDistancia = d;
                }
            }
            if (mejorDistancia > 0 && Math.min(palabra.length(), mejor.length()) < LARGO_MINIMO_PALABRA_CON_ERROR) {
                return false;
            }
            total += mejorDistancia;
            if (total > DISTANCIA_MAXIMA) {
                return false;
            }
            libresB.remove(mejor);
        }
        return true;
    }

    /** Distancia de Levenshtein: cuántas letras hay que agregar, sacar o cambiar. */
    static int distancia(String a, String b) {
        int[] anterior = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            anterior[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(Math.min(actual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + costo);
            }
            int[] tmp = anterior;
            anterior = actual;
            actual = tmp;
        }
        return anterior[b.length()];
    }
}
