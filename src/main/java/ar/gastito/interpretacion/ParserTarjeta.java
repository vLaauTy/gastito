package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo obvio de la tarjeta, sin IA: las fechas ("la tarjeta cierra el 26/11 y vence el 11/12"), el total del resumen
 * ("el resumen de la tarjeta es de 250.000"), el débito ("se debitó la tarjeta, 250.000") y la consulta ("¿cuánto
 * llevo de tarjeta?", "¿cuánto vence el 11?"). Lo que no es obvio lo decide la IA (intención TARJETA).
 */
public final class ParserTarjeta {

    private static final String MESES = "enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre"
            + "|octubre|noviembre|diciembre";
    /** "26/11", "26/11/2026", "26 de noviembre" o solo "26". */
    private static final String FECHA = "(\\d{1,2})(?:\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{2,4}))?|\\s+de\\s+(" + MESES
            + "))?(?![\\d.,]\\d)";
    private static final String RELLENO = "(?:\\s+(?:el|la|tarjeta|resumen|de|del|es|fue|va|a|ser|dia|para))*\\s+";
    private static final Pattern UNA_FECHA = Pattern.compile("^(?:el\\s+)?" + FECHA + "$");
    private static final Pattern CIERRE = Pattern.compile(
            "\\b(cierra|cerro|cierre|cerraba|cierran|cerrar)\\b" + RELLENO + FECHA);
    private static final Pattern VENCE = Pattern.compile(
            "\\b(vence|vencio|vencimiento|vto|vencen|vencia)\\b" + RELLENO + FECHA);
    private static final Pattern DE_LA_TARJETA = Pattern.compile("\\b(tarjeta|resumen|visa|master|mastercard)\\b");
    private static final Pattern PAGO = Pattern.compile(
            "\\b(?:se debito|me debitaron|debitaron|ya se debito|debito|pague|ya pague|se pago|pagamos)\\s+"
                    + "(?:el resumen|la tarjeta)\\b"
                    + "|\\b(?:tarjeta|resumen)\\b.*\\b(?:se debito|ya se debito|debitado|debitada|ya esta pagad[oa])\\b");
    private static final Pattern TOTAL = Pattern.compile(
            "\\bresumen\\b.*\\b(?:es de|son|vino de|vino|llego de|llego|dice|da|salio|sale|de)\\b"
                    + "|\\btotal de la tarjeta\\b");
    private static final Pattern CONSULTA_CON_TARJETA = Pattern.compile(
            "\\b(cuanto|cuando|llevo|llevamos|debo|vence|vencimiento|cierra|cierre|resumen|ver|mostrame|como viene"
                    + "|como va|que dia)\\b");
    private static final Pattern CONSULTA_SIN_TARJETA = Pattern.compile("^(cuanto|cuando|que dia) (vence|cierra)\\b");
    /** Lo que se gastó con la tarjeta es una pregunta sobre gastos, no sobre el resumen. */
    private static final Pattern GASTOS = Pattern.compile(
            "\\b(gaste|gasto|gastos|gastado|compre|compras|cuotas|categoria)\\b");
    private static final Pattern CAMBIO = Pattern.compile(
            "\\b(agreg\\w*|sum\\w*|anot\\w*|sac\\w*|borr\\w*|cancel\\w*|elimin\\w*|baja)\\b");
    /** Un monto: "250.000", "250000", "250.000,50", "250 mil", "250k", "250 lucas" (no un día ni una fecha). */
    private static final Pattern MONTO = Pattern.compile(
            "(?<![\\d/.,])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)(?![\\d/])\\s*(mil|k|lucas|luquitas)?\\b");

    private ParserTarjeta() {
    }

    /**
     * @return las fechas, el total, el débito o la consulta de la tarjeta; vacío si el mensaje no es obviamente
     *         sobre el resumen de la tarjeta
     */
    public static Optional<ValidadorInterpretacion.Resultado> parsear(String texto, LocalDate hoy) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        boolean pregunta = texto.contains("?");
        String t = Textos.normalizar(texto).replaceAll("[¿?¡!]", " ").replaceAll("\\s+", " ").strip();
        Matcher cierre = CIERRE.matcher(t);
        Matcher vence = VENCE.matcher(t);
        boolean conCierre = cierre.find();
        boolean conVencimiento = vence.find();
        Optional<LocalDate> fechaCierre = conCierre ? cierre(cierre, hoy) : Optional.empty();
        if (conCierre && conVencimiento) {
            Optional<LocalDate> fechaVencimiento = fecha(vence, 2, fechaCierre.orElse(hoy), Cual.DESPUES);
            if (fechaCierre.isPresent() && fechaVencimiento.isPresent()) {
                return Optional.of(new ValidadorInterpretacion.FechasTarjeta(fechaCierre.get(),
                        fechaVencimiento.get()));
            }
        }
        boolean deLaTarjeta = DE_LA_TARJETA.matcher(t).find();
        if (!deLaTarjeta && !CONSULTA_SIN_TARJETA.matcher(t).find()) {
            return Optional.empty();
        }
        // "La tarjeta cierra el 26/11" (sin el vencimiento): se pregunta el que falta.
        if (conCierre != conVencimiento && deLaTarjeta && !pregunta && !t.matches("^(cuanto|cuando|que dia)\\b.*")) {
            if (conCierre && fechaCierre.isPresent()) {
                return Optional.of(new ValidadorInterpretacion.FechasTarjeta(fechaCierre.get(), null));
            }
            Optional<LocalDate> fechaVencimiento = conVencimiento ? fecha(vence, 2, hoy, Cual.PROXIMA)
                    : Optional.empty();
            if (fechaVencimiento.isPresent()) {
                return Optional.of(new ValidadorInterpretacion.FechasTarjeta(null, fechaVencimiento.get()));
            }
        }
        Optional<BigDecimal> monto = monto(t);
        if (PAGO.matcher(t).find()) {
            return Optional.of(new ValidadorInterpretacion.PagoTarjeta(monto.orElse(null)));
        }
        if (monto.isPresent()) {
            return TOTAL.matcher(t).find() && !CAMBIO.matcher(t).find()
                    ? Optional.of(new ValidadorInterpretacion.TotalTarjeta(monto.get()))
                    : Optional.empty();
        }
        if (CAMBIO.matcher(t).find() || GASTOS.matcher(t).find()) {
            return Optional.empty();
        }
        boolean consulta = t.equals("tarjeta") || t.equals("la tarjeta") || CONSULTA_SIN_TARJETA.matcher(t).find()
                || (t.contains("tarjeta") && CONSULTA_CON_TARJETA.matcher(t).find());
        return consulta ? Optional.of(new ValidadorInterpretacion.Ver(ValidadorInterpretacion.Lista.TARJETA))
                : Optional.empty();
    }

    /** Un cierre dicho solo ("26/11", "el 26", "26 de noviembre", "2026-11-26"): el próximo. */
    public static Optional<LocalDate> cierre(String texto, LocalDate hoy) {
        return fechaSola(texto, hoy, Cual.PROXIMA);
    }

    /** Un vencimiento dicho solo: el primero después del cierre. */
    public static Optional<LocalDate> vencimiento(String texto, LocalDate cierre) {
        return fechaSola(texto, cierre, Cual.DESPUES);
    }

    private static Optional<LocalDate> fechaSola(String texto, LocalDate referencia, Cual cual) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        Matcher m = UNA_FECHA.matcher(Textos.normalizar(texto).strip());
        if (m.matches()) {
            return fecha(m, 1, referencia, cual);
        }
        try {
            return Optional.of(LocalDate.parse(texto.strip()));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** "Cerró el 29/10" habla de uno que ya pasó; "cierra el 26" del próximo. */
    private static Optional<LocalDate> cierre(Matcher m, LocalDate hoy) {
        boolean pasado = m.group(1).startsWith("cerr") && !m.group(1).equals("cerrar");
        return fecha(m, 2, hoy, pasado ? Cual.ANTERIOR : Cual.PROXIMA);
    }

    /** Cuál de las fechas posibles (sin año, o sin mes) se toma, según la referencia. */
    private enum Cual {
        /** La más cercana desde la referencia en adelante (o la más cercana de todas, si no hay). */
        PROXIMA,
        /** La más cercana hasta la referencia (o la más cercana de todas, si no hay). */
        ANTERIOR,
        /** La primera después de la referencia. */
        DESPUES
    }

    /** Lee la fecha que empieza en el grupo {@code g} (día, mes, año, mes en palabras). */
    private static Optional<LocalDate> fecha(Matcher m, int g, LocalDate referencia, Cual cual) {
        try {
            int dia = Integer.parseInt(m.group(g));
            Integer mes = m.group(g + 1) != null ? Integer.valueOf(m.group(g + 1))
                    : m.group(g + 3) != null ? mesEnPalabras(m.group(g + 3)) : null;
            if (m.group(g + 2) != null) {
                int anio = Integer.parseInt(m.group(g + 2));
                return Optional.of(LocalDate.of(anio < 100 ? 2000 + anio : anio, mes, dia));
            }
            List<LocalDate> candidatas = new ArrayList<>();
            for (int i = -1; i <= 2; i++) {
                try {
                    candidatas.add(mes != null ? LocalDate.of(referencia.getYear() + i, mes, dia)
                            : referencia.plusMonths(i).withDayOfMonth(dia));
                } catch (DateTimeException e) {
                    // ese mes no tiene ese día
                }
            }
            Comparator<LocalDate> cercania = Comparator.comparingLong(
                    c -> Math.abs(ChronoUnit.DAYS.between(referencia, c)));
            return switch (cual) {
                case DESPUES -> candidatas.stream().filter(c -> c.isAfter(referencia)).min(cercania);
                case ANTERIOR -> candidatas.stream().filter(c -> !c.isAfter(referencia)).min(cercania)
                        .or(() -> candidatas.stream().min(cercania));
                case PROXIMA -> candidatas.stream().filter(c -> !c.isBefore(referencia)).min(cercania)
                        .or(() -> candidatas.stream().min(cercania));
            };
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int mesEnPalabras(String mes) {
        List<String> meses = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
                "septiembre", "octubre", "noviembre", "diciembre");
        return mes.equals("setiembre") ? 9 : meses.indexOf(mes) + 1;
    }

    /** El monto más grande del mensaje (de 100 para arriba, o con "mil"), sin contar días ni fechas. */
    static Optional<BigDecimal> monto(String normalizado) {
        Matcher m = MONTO.matcher(normalizado);
        BigDecimal mayor = null;
        while (m.find()) {
            String sufijo = m.group(2) == null ? "" : m.group(2).startsWith("l") ? " mil" : " " + m.group(2);
            Optional<BigDecimal> monto = Montos.parsearConPalabras(m.group(1) + sufijo);
            if (monto.isPresent() && (m.group(2) != null || monto.get().compareTo(BigDecimal.valueOf(100)) >= 0)
                    && (mayor == null || monto.get().compareTo(mayor) > 0)) {
                mayor = monto.get();
            }
        }
        return Optional.ofNullable(mayor);
    }
}
