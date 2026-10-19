package ar.gastito.interpretacion;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ar.gastito.catalogo.ConPalabrasClave;

/** Utilidades para comparar lo que escribe el usuario con los nombres de la base. */
final class Textos {

    private Textos() {
    }

    /** Minúsculas y sin tildes: "Débito" → "debito". */
    static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).trim();
    }

    /** Palabras clave normalizadas, más el nombre si es una sola palabra. */
    static Set<String> palabrasClave(ConPalabrasClave elemento) {
        Stream<String> palabras = elemento.getPalabrasClave() == null
                ? Stream.empty()
                : Arrays.stream(elemento.getPalabrasClave().split(","));
        return Stream.concat(palabras, Stream.of(elemento.getNombre()))
                .map(Textos::normalizar)
                .filter(p -> !p.isEmpty() && !p.contains(" "))
                .collect(Collectors.toSet());
    }

    static String capitalizar(String palabra) {
        if (palabra.isEmpty()) {
            return palabra;
        }
        return palabra.substring(0, 1).toUpperCase(Locale.ROOT) + palabra.substring(1);
    }
}
