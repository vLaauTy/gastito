package ar.gastito.sueldo.escala;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** El mes del encabezado de una columna de la hoja: "oct-26", "Octubre 2026", "10/2026" o "2026-10". */
public final class MesHoja {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final List<String> MESES = List.of("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep",
            "oct", "nov", "dic");

    private static final Pattern CON_NOMBRE = Pattern.compile("([a-z]{3,})\\.?[\\s\\-/.]*(?:de)?\\s*(\\d{2}|\\d{4})");
    private static final Pattern ISO = Pattern.compile("(\\d{4})[-/](\\d{1,2})");
    private static final Pattern NUMERICO = Pattern.compile("(\\d{1,2})[-/](\\d{2}|\\d{4})");

    private MesHoja() {
    }

    public static Optional<YearMonth> leer(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
        Matcher m = CON_NOMBRE.matcher(t);
        if (m.matches()) {
            String nombre = m.group(1).startsWith("set") ? "sep" : m.group(1).substring(0, 3);
            int mes = MESES.indexOf(nombre) + 1;
            return mes == 0 ? Optional.empty() : armar(anio(m.group(2)), mes);
        }
        m = ISO.matcher(t);
        if (m.matches()) {
            return armar(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        m = NUMERICO.matcher(t);
        if (m.matches()) {
            return armar(anio(m.group(2)), Integer.parseInt(m.group(1)));
        }
        return Optional.empty();
    }

    /** "oct-26", como lo escribe la hoja. */
    public static String corto(YearMonth mes) {
        return mes.getMonth().getDisplayName(TextStyle.SHORT, ES_AR).replace(".", "").substring(0, 3)
                .toLowerCase(Locale.ROOT) + "-" + "%02d".formatted(mes.getYear() % 100);
    }

    private static int anio(String texto) {
        int anio = Integer.parseInt(texto);
        return anio < 100 ? 2000 + anio : anio;
    }

    private static Optional<YearMonth> armar(int anio, int mes) {
        return mes >= 1 && mes <= 12 && anio >= 2000 && anio <= 2100 ? Optional.of(YearMonth.of(anio, mes))
                : Optional.empty();
    }
}
