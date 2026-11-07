package ar.gastito.viaje;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ar.gastito.interpretacion.Montos;

/**
 * Los pedidos de viajes, sin IA, dichos como sea: "me voy a Bariloche del 10/1 al 17/1, somos 2, presupuesto
 * 1.500.000", "para Bariloche me falta el hospedaje, ≈ 400.000", "¿cómo va Bariloche?", "/viajes". Lo que no se
 * reconoce sigue el camino de siempre (la IA).
 */
public final class ParserViaje {

    public sealed interface Pedido {
    }

    /** "Me voy a Bariloche del 10/1 al 17/1": un viaje nuevo, para confirmar. */
    public record Nuevo(String nombre, LocalDate desde, LocalDate hasta, Integer personas, BigDecimal presupuesto)
            implements Pedido {
    }

    /** "Para Bariloche me falta el hospedaje, ≈ 400.000". */
    public record Falta(Viaje viaje, String descripcion, BigDecimal monto) implements Pedido {
    }

    /**
     * "El préstamo a lucas es del viaje a Bariloche" (viaje nombrado) o "el préstamo a lucas no es del viaje" (viaje
     * null): cambiar el viaje de un préstamo ya cargado.
     */
    public record PrestamoDelViaje(String persona, Viaje viaje) implements Pedido {
    }

    /** "¿Cómo va Bariloche?", "/viajes": el panel (del viaje nombrado, o del actual o próximo). */
    public record Consulta(Viaje viaje) implements Pedido {
    }

    private static final Map<String, Integer> MESES = Map.ofEntries(Map.entry("enero", 1), Map.entry("febrero", 2),
            Map.entry("marzo", 3), Map.entry("abril", 4), Map.entry("mayo", 5), Map.entry("junio", 6),
            Map.entry("julio", 7), Map.entry("agosto", 8), Map.entry("septiembre", 9), Map.entry("setiembre", 9),
            Map.entry("octubre", 10), Map.entry("noviembre", 11), Map.entry("diciembre", 12));

    private static final String FECHA = "\\d{1,2}(?:/\\d{1,2}(?:/\\d{2,4})?)?(?: de [a-z]+)?";
    private static final String MONTO = "\\$?\\s*\\d[\\d.,]*(?:\\s*(?:mil|k|millones|millon|m))?";

    private static final Pattern NUEVO = Pattern.compile("^(?:me voy|nos vamos|vamos|viajo|viajamos|me voy de viaje"
            + "|nos vamos de viaje|agrega(?:me)? un viaje|anota(?:me)? un viaje|nuevo viaje|viaje)(?: de vacaciones)?"
            + " (?:a|para) (?<nombre>.+?) (?:del?|desde el) (?<desde>" + FECHA + ") (?:al?|hasta el) (?<hasta>"
            + FECHA + ")(?<resto>.*)$");
    private static final Pattern PERSONAS = Pattern.compile("\\b(?:somos|vamos|viajamos) (\\d{1,2})\\b"
            + "|\\b(\\d{1,2}) personas\\b");
    private static final Pattern PRESUPUESTO = Pattern.compile("\\bpresupuesto(?: de)? (" + MONTO + ")");
    private static final Pattern FALTA = Pattern.compile("^(?:para|del?) (?:el viaje (?:a|de) )?(?<viaje>.+?)"
            + " (?:me |nos )?(?:falta|faltan)(?: pagar)? (?<desc>.+?)"
            + "(?:\\s*(?:,|≈|~|de|por|unos|unas|como|que (?:son|sale|salen))\\s*(?:≈|~|unos|unas|como)?\\s*"
            + "(?<monto>" + MONTO + "))?\\s*$");
    private static final String PRESTAMO = "^(?:el prestamo|la plata que le preste|lo que le preste)"
            + " (?:a|de|para) (?<persona>.+?) ";
    private static final Pattern PRESTAMO_DEL_VIAJE = Pattern.compile(PRESTAMO
            + "(?:es|va|era|fue|corresponde)(?: parte)? (?:del|al|para el|de|a) (?:viaje (?:a|de|para) )?"
            + "(?<viaje>.+?)\\.?$");
    private static final Pattern PRESTAMO_SIN_VIAJE = Pattern.compile(PRESTAMO
            + "no (?:es|va|era|fue|corresponde)(?: parte)? (?:del|de ningun|de un|al) viaje.*$");
    private static final Pattern ARTICULO = Pattern.compile("^(?:el|la|los|las|un|una|pagar) ");
    private static final Pattern PREGUNTA = Pattern.compile("\\b(como va|como vengo|cuanto|ahorrar|separar|"
            + "falta|faltan|resumen|mostrame|ver)\\b");

    private ParserViaje() {
    }

    /** @param viajes los viajes cargados, para reconocerlos por el nombre */
    public static Optional<Pedido> parsear(String texto, List<Viaje> viajes, LocalDate hoy) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String t = normalizar(texto);
        if (t.equals("/viaje") || t.equals("/viajes") || t.matches("^(como va|como vengo con) (el|mi) viaje$")) {
            return Optional.of(new Consulta(null));
        }
        Matcher sin = PRESTAMO_SIN_VIAJE.matcher(t);
        if (sin.matches()) {
            return Optional.of(new PrestamoDelViaje(nombreOriginal(texto, sin.group("persona")), null));
        }
        Matcher prestamo = PRESTAMO_DEL_VIAJE.matcher(t);
        if (prestamo.matches()) {
            Optional<Viaje> viaje = porNombre(prestamo.group("viaje"), viajes);
            if (viaje.isPresent()) {
                return Optional.of(new PrestamoDelViaje(nombreOriginal(texto, prestamo.group("persona")),
                        viaje.get()));
            }
        }
        Matcher nuevo = NUEVO.matcher(t);
        if (nuevo.matches()) {
            Optional<Pedido> n = nuevo(texto, nuevo, hoy);
            if (n.isPresent()) {
                return n;
            }
        }
        Matcher falta = FALTA.matcher(t);
        if (falta.matches()) {
            Optional<Viaje> viaje = porNombre(falta.group("viaje"), viajes);
            if (viaje.isPresent()) {
                String desc = ARTICULO.matcher(falta.group("desc").strip()).replaceFirst("").strip();
                BigDecimal monto = falta.group("monto") == null ? null : monto(falta.group("monto")).orElse(null);
                if (!desc.isEmpty() && !desc.matches("[\\d.,$ ]+")) {
                    return Optional.of(new Falta(viaje.get(), desc, monto));
                }
            }
        }
        // "¿Cómo va Bariloche?", "¿cuánto tengo que ahorrar para Bariloche?": sin monto, nombrando un viaje.
        if (!t.matches(".*\\d{3,}.*") && PREGUNTA.matcher(t).find()) {
            Optional<Viaje> nombrado = viajes.stream().filter(v -> (" " + t + " ").contains(" "
                    + normalizar(v.getNombre()) + " ")).findFirst();
            if (nombrado.isPresent()) {
                return Optional.of(new Consulta(nombrado.get()));
            }
        }
        return Optional.empty();
    }

    private static Optional<Pedido> nuevo(String original, Matcher m, LocalDate hoy) {
        String nombre = nombreOriginal(original, m.group("nombre"));
        String resto = m.group("resto") == null ? "" : m.group("resto");
        Optional<int[]> hasta = diaMes(m.group("hasta"), null);
        if (hasta.isEmpty()) {
            return Optional.empty();
        }
        Optional<int[]> desde = diaMes(m.group("desde"), hasta.get()[1]);
        if (desde.isEmpty()) {
            return Optional.empty();
        }
        try {
            LocalDate d = fecha(desde.get(), hoy);
            LocalDate h = fecha(hasta.get(), d);
            Integer personas = null;
            Matcher p = PERSONAS.matcher(resto);
            if (p.find()) {
                personas = Integer.parseInt(p.group(1) != null ? p.group(1) : p.group(2));
            }
            BigDecimal presupuesto = null;
            Matcher pr = PRESUPUESTO.matcher(resto);
            if (pr.find()) {
                presupuesto = monto(pr.group(1)).orElse(null);
            }
            return Optional.of(new Nuevo(nombre, d, h, personas == null || personas < 1 ? null : personas,
                    presupuesto));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** El nombre como lo escribió (con mayúsculas y tildes), sacado del texto original. */
    private static String nombreOriginal(String original, String normalizado) {
        String[] palabras = original.strip().split("\\s+");
        int largo = normalizado.split(" ").length;
        for (int i = 0; i + largo <= palabras.length; i++) {
            String candidato = String.join(" ", java.util.Arrays.copyOfRange(palabras, i, i + largo));
            if (normalizar(candidato).equals(normalizado)) {
                return Character.toUpperCase(candidato.charAt(0)) + candidato.substring(1);
            }
        }
        return Character.toUpperCase(normalizado.charAt(0)) + normalizado.substring(1);
    }

    /**
     * "10/1", "10/1/2027", "10 de enero" o "10" (con el mes de la otra fecha) → {día, mes, año o 0}.
     *
     * @param mesSiFalta el mes de la otra fecha, o null
     */
    private static Optional<int[]> diaMes(String texto, Integer mesSiFalta) {
        Matcher m = Pattern.compile("^(\\d{1,2})(?:/(\\d{1,2})(?:/(\\d{2,4}))?)?(?: de ([a-z]+))?$").matcher(texto);
        if (!m.matches()) {
            return Optional.empty();
        }
        int dia = Integer.parseInt(m.group(1));
        Integer mes = m.group(2) != null ? Integer.valueOf(m.group(2))
                : m.group(4) != null ? MESES.get(m.group(4)) : mesSiFalta;
        if (mes == null) {
            return Optional.empty();
        }
        int anio = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return Optional.of(new int[] {dia, mes, anio < 100 && anio > 0 ? 2000 + anio : anio});
    }

    /** Sin año: la próxima vez que llega esa fecha desde {@code desde} (incluido). */
    private static LocalDate fecha(int[] dma, LocalDate desde) {
        if (dma[2] > 0) {
            return LocalDate.of(dma[2], dma[1], dma[0]);
        }
        LocalDate f = LocalDate.of(desde.getYear(), dma[1], dma[0]);
        return f.isBefore(desde) ? f.plusYears(1) : f;
    }

    private static Optional<Viaje> porNombre(String nombre, List<Viaje> viajes) {
        String buscado = normalizar(nombre);
        return viajes.stream().filter(v -> normalizar(v.getNombre()).equals(buscado)).findFirst();
    }

    private static Optional<BigDecimal> monto(String texto) {
        String t = texto.replace("$", "").strip().replace("millones", "M").replace("millon", "M");
        return Montos.parsearConPalabras(t);
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[¿?¡!]", "").replaceAll("\\s+", " ").strip();
    }
}
