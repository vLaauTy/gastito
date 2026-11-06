package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plata que le presté a alguien y me devuelve en cuotas, como lo dijo: "30.000 son de Martín, que me los devuelve en
 * 6 cuotas de 5.000 desde diciembre". Lo que falta (el valor de la cuota, la cantidad) lo calcula
 * ar.gastito.prestamo.PlanPrestamo.
 *
 * @param monto     cuánto le presté, o null si dijo las cuotas y su valor
 * @param primerMes el mes de la primera cuota, o null si no lo dijo
 * @param dia       el día aproximado en que paga, o null
 */
public record PedidoPrestamo(String persona, BigDecimal monto, Integer cuotas, BigDecimal valorCuota,
        YearMonth primerMes, Integer dia) {

    static final int MAXIMO_CUOTAS = 120;

    private static final Map<String, Month> MESES = Map.ofEntries(Map.entry("enero", Month.JANUARY),
            Map.entry("febrero", Month.FEBRUARY), Map.entry("marzo", Month.MARCH), Map.entry("abril", Month.APRIL),
            Map.entry("mayo", Month.MAY), Map.entry("junio", Month.JUNE), Map.entry("julio", Month.JULY),
            Map.entry("agosto", Month.AUGUST), Map.entry("septiembre", Month.SEPTEMBER),
            Map.entry("setiembre", Month.SEPTEMBER), Map.entry("octubre", Month.OCTOBER),
            Map.entry("noviembre", Month.NOVEMBER), Map.entry("diciembre", Month.DECEMBER));

    private static final Pattern NUMERICO = Pattern.compile("^(\\d{1,2})[/-](\\d{2}|\\d{4})$");
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})$");
    private static final Pattern ANIO = Pattern.compile("\\b(20\\d{2})\\b");

    /**
     * Lo que dijo la IA, controlado. Null si no es un préstamo: sin a quién, o sin forma de saber cuánto.
     *
     * @param desde el mes como lo dijo ("diciembre", "el mes que viene", "12/2026")
     */
    static PedidoPrestamo de(String persona, String monto, String cuotas, String valorCuota, String desde,
            String dia, LocalDate hoy) {
        if (persona == null || persona.isBlank()) {
            return null;
        }
        BigDecimal total = ValidadorInterpretacion.monto(monto).orElse(null);
        BigDecimal valor = ValidadorInterpretacion.monto(valorCuota).orElse(null);
        Integer n = entero(cuotas, 1, MAXIMO_CUOTAS);
        if (total == null && (n == null || valor == null)) {
            return null;
        }
        return new PedidoPrestamo(Textos.capitalizar(persona.strip()), total, n, valor, mes(desde, hoy),
                entero(dia, 1, 31));
    }

    private static Integer entero(String texto, int minimo, int maximo) {
        if (texto == null || !texto.strip().matches("\\d{1,3}")) {
            return null;
        }
        int n = Integer.parseInt(texto.strip());
        return n < minimo || n > maximo ? null : n;
    }

    /**
     * "diciembre" (este año si todavía no pasó, si no el que viene), "enero 2027", "12/2026", "2026-12", "el mes que
     * viene", "este mes". Null si no se entiende.
     */
    static YearMonth mes(String texto, LocalDate hoy) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String t = Textos.normalizar(texto).replaceAll("[.,]", " ").replaceAll("\\s+", " ").strip();
        YearMonth actual = YearMonth.from(hoy);
        if (t.contains("que viene") || t.contains("proximo mes") || t.contains("mes siguiente")) {
            return actual.plusMonths(1);
        }
        if (t.contains("este mes")) {
            return actual;
        }
        Matcher numerico = NUMERICO.matcher(t);
        if (numerico.matches()) {
            int mes = Integer.parseInt(numerico.group(1));
            int anio = Integer.parseInt(numerico.group(2));
            return mes < 1 || mes > 12 ? null : YearMonth.of(anio < 100 ? 2000 + anio : anio, mes);
        }
        Matcher iso = ISO.matcher(t);
        if (iso.matches()) {
            int mes = Integer.parseInt(iso.group(2));
            return mes < 1 || mes > 12 ? null : YearMonth.of(Integer.parseInt(iso.group(1)), mes);
        }
        for (Map.Entry<String, Month> e : MESES.entrySet()) {
            if (t.matches(".*\\b" + e.getKey() + "\\b.*")) {
                Matcher anio = ANIO.matcher(t);
                if (anio.find()) {
                    return YearMonth.of(Integer.parseInt(anio.group(1)), e.getValue());
                }
                YearMonth esteAnio = YearMonth.of(hoy.getYear(), e.getValue());
                return esteAnio.isBefore(actual) ? esteAnio.plusYears(1) : esteAnio;
            }
        }
        return null;
    }
}
