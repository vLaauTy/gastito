package ar.gastito.prestamo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Las cuentas de un préstamo en cuotas (regla 2: las hace Java, no la IA): el dato que falte entre el total, la
 * cantidad de cuotas y el valor de cada una, y cómo se acomodan las cuotas que siguen cuando me pagan de más o de
 * menos. Las cuotas van redondeadas a peso, para abajo; la diferencia queda en la última.
 */
public final class PlanPrestamo {

    /** Un préstamo no se divide en más cuotas que esto. */
    static final int MAXIMO_CUOTAS = 120;

    private PlanPrestamo() {
    }

    /** @param montos el valor de cada cuota, en orden (suman el total) */
    public record Plan(BigDecimal monto, int cuotas, BigDecimal valorCuota, List<BigDecimal> montos) {
    }

    /**
     * El plan con lo que se dijo. Si se dicen los tres datos y no cierran, mandan el total y la cantidad.
     * <ul>
     * <li>Cuotas y valor, sin total: total = cuotas × valor.</li>
     * <li>Total y cuotas: valor = total ÷ cuotas (a peso; la última absorbe el redondeo).</li>
     * <li>Total y valor: tantas cuotas como hagan falta; la última, lo que queda.</li>
     * <li>Solo el total: una cuota.</li>
     * </ul>
     *
     * @throws PrestamoInvalidoException si no hay forma de saber el total
     */
    public static Plan de(BigDecimal monto, Integer cuotas, BigDecimal valorCuota) {
        if (cuotas != null && (cuotas < 1 || cuotas > MAXIMO_CUOTAS)) {
            cuotas = null;
        }
        if (valorCuota != null && valorCuota.signum() <= 0) {
            valorCuota = null;
        }
        if (monto == null || monto.signum() <= 0) {
            if (cuotas == null || valorCuota == null) {
                throw new PrestamoInvalidoException("Falta cuánto le prestaste");
            }
            monto = valorCuota.multiply(BigDecimal.valueOf(cuotas));
        }
        monto = monto.setScale(2, RoundingMode.HALF_UP);
        if (cuotas == null && valorCuota != null) {
            if (valorCuota.compareTo(monto) >= 0) {
                cuotas = 1;
            } else {
                int n = monto.divide(valorCuota, 0, RoundingMode.CEILING).intValueExact();
                if (n > MAXIMO_CUOTAS) {
                    throw new PrestamoInvalidoException("Son más de " + MAXIMO_CUOTAS + " cuotas");
                }
                List<BigDecimal> montos = new ArrayList<>();
                BigDecimal valor = valorCuota.setScale(2, RoundingMode.HALF_UP);
                for (int i = 0; i < n - 1; i++) {
                    montos.add(valor);
                }
                montos.add(monto.subtract(valor.multiply(BigDecimal.valueOf(n - 1))));
                return new Plan(monto, n, valor, montos);
            }
        }
        int n = cuotas == null ? 1 : cuotas;
        List<BigDecimal> montos = repartir(monto, n);
        return new Plan(monto, n, montos.get(0), montos);
    }

    /** {@code total} en {@code n} cuotas iguales, a peso para abajo; la última, con lo que sobra del redondeo. */
    static List<BigDecimal> repartir(BigDecimal total, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Al menos una cuota");
        }
        BigDecimal cada = total.divide(BigDecimal.valueOf(n), 0, RoundingMode.DOWN).setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> montos = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            montos.add(cada);
        }
        montos.add(total.subtract(cada.multiply(BigDecimal.valueOf(n - 1))).setScale(2, RoundingMode.HALF_UP));
        return montos;
    }

    /**
     * Cómo queda después de un pago.
     *
     * @param cubiertas cuántas de las pendientes quedan cobradas (al menos una)
     * @param cobrado   lo que se anota en cada cuota cubierta, en orden (suman el pago, salvo el excedente)
     * @param siguientes el nuevo valor de cada pendiente que sigue, en orden; si me debe más de lo que cubren las
     *                   que quedan (pagó de menos la última), una más al final
     * @param excedente lo que pagó de más sobre todo lo que me debía (cero si no)
     */
    public record Aplicacion(int cubiertas, List<BigDecimal> cobrado, List<BigDecimal> siguientes,
            BigDecimal excedente) {
    }

    /**
     * Un pago contra las cuotas pendientes, de la más vieja a la más nueva. Cubre todas las que alcanza enteras (y al
     * menos la primera, aunque pague menos). Si pagó justo, las demás quedan como estaban; si pagó de más o de menos,
     * lo que todavía me debe se reparte en partes iguales entre las que siguen.
     */
    public static Aplicacion aplicar(List<BigDecimal> pendientes, BigDecimal pago) {
        if (pendientes.isEmpty()) {
            throw new PrestamoInvalidoException("Ya no te debe nada");
        }
        if (pago == null || pago.signum() <= 0) {
            throw new PrestamoInvalidoException("El pago tiene que ser mayor a cero");
        }
        BigDecimal debe = pendientes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (pago.compareTo(debe) >= 0) {
            return new Aplicacion(pendientes.size(), List.copyOf(pendientes), List.of(), pago.subtract(debe));
        }
        int k = 0;
        BigDecimal acumulado = BigDecimal.ZERO;
        while (k < pendientes.size() && acumulado.add(pendientes.get(k)).compareTo(pago) <= 0) {
            acumulado = acumulado.add(pendientes.get(k));
            k++;
        }
        boolean justo = acumulado.compareTo(pago) == 0;
        if (k == 0) {
            k = 1;
        }
        List<BigDecimal> cobrado = new ArrayList<>(pendientes.subList(0, k));
        BigDecimal antesDeLaUltima = cobrado.subList(0, k - 1).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        cobrado.set(k - 1, pago.subtract(antesDeLaUltima));
        List<BigDecimal> resto = pendientes.subList(k, pendientes.size());
        if (justo) {
            return new Aplicacion(k, cobrado, List.copyOf(resto), BigDecimal.ZERO);
        }
        BigDecimal falta = debe.subtract(pago);
        List<BigDecimal> siguientes = resto.isEmpty() ? List.of(falta.setScale(2, RoundingMode.HALF_UP))
                : repartir(falta, resto.size());
        return new Aplicacion(k, cobrado, siguientes, BigDecimal.ZERO);
    }
}
