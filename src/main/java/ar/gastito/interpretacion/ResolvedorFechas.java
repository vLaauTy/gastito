package ar.gastito.interpretacion;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Convierte la fecha que extrae la IA ("ayer", "sábado", "12/10") en una fecha y hora concreta.
 * El cálculo se hace acá y no en la IA. "Ahora" ya viene en hora de Argentina.
 * <ul>
 * <li>Vacío o desconocido: hoy.</li>
 * <li>Día de la semana: el último que pasó, sin contar hoy ("el sábado" dicho un sábado es hace 7 días).</li>
 * <li>dd/mm sin año: este año, o el anterior si todavía no llegó.</li>
 * <li>Nunca devuelve una fecha futura: si pasa, usa hoy.</li>
 * <li>Sin hora: si es hoy, la hora actual; si es otro día, las 12:00.</li>
 * </ul>
 */
@Component
public class ResolvedorFechas {

    static final LocalTime HORA_POR_DEFECTO = LocalTime.NOON;

    private static final Map<String, DayOfWeek> DIAS = Map.of(
            "lunes", DayOfWeek.MONDAY,
            "martes", DayOfWeek.TUESDAY,
            "miercoles", DayOfWeek.WEDNESDAY,
            "jueves", DayOfWeek.THURSDAY,
            "viernes", DayOfWeek.FRIDAY,
            "sabado", DayOfWeek.SATURDAY,
            "domingo", DayOfWeek.SUNDAY);

    private static final Pattern DD_MM = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{4}|\\d{2}))?");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    /** "21:30", "9.05", "21hs" o, como en los tickets, con segundos: "17:44:28" (los segundos no se usan). */
    private static final Pattern HORA = Pattern.compile("(\\d{1,2})(?:[:.](\\d{2})(?::\\d{2})?)?\\s*(?:h|hs)?");

    public LocalDateTime resolver(String fecha, String hora, LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        LocalDate dia = resolverDia(fecha, hoy).filter(d -> !d.isAfter(hoy)).orElse(hoy);
        Optional<LocalTime> horaExplicita = resolverHora(hora);
        if (horaExplicita.isPresent()) {
            return dia.atTime(horaExplicita.get());
        }
        return dia.equals(hoy) ? ahora.truncatedTo(ChronoUnit.MINUTES) : dia.atTime(HORA_POR_DEFECTO);
    }

    /**
     * Lee una fecha escrita por el usuario al editar: "ayer", "12/10", "sábado 21:30", "12/10 9:15" o solo la hora
     * ("21:30", que es hoy). A diferencia de {@link #resolver}, si no la entiende o es futura devuelve vacío.
     */
    public Optional<LocalDateTime> resolverTexto(String texto, LocalDateTime ahora) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String[] partes = texto.trim().split("\\s+");
        String ultima = partes[partes.length - 1];
        Optional<LocalTime> hora = ultima.contains(":") || ultima.toLowerCase().endsWith("hs")
                ? resolverHora(ultima)
                : Optional.empty();
        String fecha = hora.isPresent()
                ? String.join(" ", java.util.Arrays.copyOf(partes, partes.length - 1))
                : texto.trim();
        LocalDate hoy = ahora.toLocalDate();
        Optional<LocalDate> dia = fecha.isBlank() ? Optional.of(hoy) : resolverDia(fecha, hoy);
        if (dia.isEmpty() || dia.get().isAfter(hoy)) {
            return Optional.empty();
        }
        LocalDateTime resultado = dia.get().atTime(hora.orElse(
                dia.get().equals(hoy) ? ahora.toLocalTime().truncatedTo(ChronoUnit.MINUTES) : HORA_POR_DEFECTO));
        return resultado.isAfter(ahora) ? Optional.empty() : Optional.of(resultado);
    }

    Optional<LocalDate> resolverDia(String fecha, LocalDate hoy) {
        if (fecha == null || fecha.isBlank()) {
            return Optional.empty();
        }
        String f = Textos.normalizar(fecha);
        if (f.contains("anteayer") || f.contains("antes de ayer")) {
            return Optional.of(hoy.minusDays(2));
        }
        if (f.contains("ayer")) {
            return Optional.of(hoy.minusDays(1));
        }
        if (f.contains("hoy")) {
            return Optional.of(hoy);
        }
        for (Map.Entry<String, DayOfWeek> dia : DIAS.entrySet()) {
            if (f.contains(dia.getKey())) {
                return Optional.of(hoy.with(TemporalAdjusters.previous(dia.getValue())));
            }
        }
        try {
            Matcher iso = ISO.matcher(f);
            if (iso.find()) {
                return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3))));
            }
            Matcher ddmm = DD_MM.matcher(f);
            if (ddmm.find()) {
                int d = Integer.parseInt(ddmm.group(1));
                int m = Integer.parseInt(ddmm.group(2));
                if (ddmm.group(3) != null) {
                    int anio = Integer.parseInt(ddmm.group(3));
                    return Optional.of(LocalDate.of(anio < 100 ? 2000 + anio : anio, m, d));
                }
                LocalDate esteAnio = LocalDate.of(hoy.getYear(), m, d);
                return Optional.of(esteAnio.isAfter(hoy) ? esteAnio.minusYears(1) : esteAnio);
            }
        } catch (DateTimeException e) {
            return Optional.empty(); // fecha imposible, como 31/02
        }
        return Optional.empty();
    }

    Optional<LocalTime> resolverHora(String hora) {
        if (hora == null || hora.isBlank()) {
            return Optional.empty();
        }
        Matcher m = HORA.matcher(hora.trim().toLowerCase());
        if (!m.matches()) {
            return Optional.empty();
        }
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.of(h, min));
    }
}
