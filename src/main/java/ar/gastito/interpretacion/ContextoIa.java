package ar.gastito.interpretacion;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.movimiento.Movimiento;

/** Cómo se le presentan a la IA el catálogo y los ejemplos, igual para un texto, un audio o un ticket. */
public final class ContextoIa {

    private static final DateTimeFormatter FORMATO_HOY =
            DateTimeFormatter.ofPattern("EEEE d/M/yyyy", Locale.forLanguageTag("es-AR"));

    private ContextoIa() {
    }

    /** "- Kiosco: golosinas, bebidas…". */
    public static String categorias(List<Categoria> categorias) {
        return categorias.stream()
                .map(c -> c.getDescripcion() == null || c.getDescripcion().isBlank()
                        ? "- " + c.getNombre()
                        : "- " + c.getNombre() + ": " + c.getDescripcion())
                .collect(Collectors.joining("\n"));
    }

    /** "- "Pan de pancho y papas" → Supermercado". */
    public static String ejemplos(List<Movimiento> confirmados) {
        if (confirmados.isEmpty()) {
            return "(todavía no hay)";
        }
        return confirmados.stream()
                .map(m -> "- \"" + m.getDescripcion() + "\" → " + m.getCategoria().getNombre())
                .collect(Collectors.joining("\n"));
    }

    /** "- Galicia". */
    public static String lista(List<? extends ConPalabrasClave> opciones) {
        return opciones.stream().map(o -> "- " + o.getNombre()).collect(Collectors.joining("\n"));
    }

    /** "jueves 22/10/2026". */
    public static String hoy(LocalDateTime ahora) {
        return FORMATO_HOY.format(ahora);
    }
}
