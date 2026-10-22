package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lee montos escritos a la argentina: el punto separa miles y la coma, decimales.
 * Acepta "1500", "$1500", "1.500", "1.500,50", "1500,5" y también "1500.50" (punto seguido de 1 o 2 dígitos).
 */
public final class Montos {

    private static final Pattern CON_MILES = Pattern.compile("\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?");
    private static final Pattern SIN_MILES = Pattern.compile("\\d+(,\\d{1,2})?");
    private static final Pattern DECIMAL_CON_PUNTO = Pattern.compile("\\d+\\.\\d{1,2}");

    private Montos() {
    }

    public static Optional<BigDecimal> parsear(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = texto.trim();
        if (t.startsWith("$")) {
            t = t.substring(1).trim();
        }
        String normalizado;
        if (CON_MILES.matcher(t).matches()) {
            normalizado = t.replace(".", "").replace(',', '.');
        } else if (SIN_MILES.matcher(t).matches()) {
            normalizado = t.replace(',', '.');
        } else if (DECIMAL_CON_PUNTO.matcher(t).matches()) {
            normalizado = t;
        } else {
            return Optional.empty();
        }
        BigDecimal monto = new BigDecimal(normalizado);
        return monto.signum() > 0 ? Optional.of(monto) : Optional.empty();
    }

    private static final Pattern CON_PALABRAS =
            Pattern.compile("(?i)^(.+?)\\s*(mil|k|millon|millón|millones|m)$");

    /**
     * Como {@link #parsear}, y además "380 mil", "380k", "1,5 millones" o "2M". Para los comandos de ingresos;
     * el parser simple de gastos sigue usando {@link #parsear}.
     */
    public static Optional<BigDecimal> parsearConPalabras(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        var m = CON_PALABRAS.matcher(texto.trim());
        if (!m.matches()) {
            return parsear(texto);
        }
        BigDecimal factor = m.group(2).equalsIgnoreCase("mil") || m.group(2).equalsIgnoreCase("k")
                ? BigDecimal.valueOf(1_000)
                : BigDecimal.valueOf(1_000_000);
        return parsear(m.group(1)).map(n -> n.multiply(factor).stripTrailingZeros())
                .map(n -> n.scale() < 0 ? n.setScale(0) : n);
    }
}
