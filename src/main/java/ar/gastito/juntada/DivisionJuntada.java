package ar.gastito.juntada;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Cómo se reparte el total de una juntada (regla 2: lo hace Java, no la IA). Las partes de los demás van
 * redondeadas a peso, para abajo; la diferencia del redondeo queda en mi parte. Las partes fijas (cambiadas a mano
 * o ya pagadas) no se tocan: el resto se reparte entre las demás y la mía, para que el total siga cerrando.
 */
public final class DivisionJuntada {

    private DivisionJuntada() {
    }

    /** La parte de uno de los demás: su monto actual y si está fija. */
    public record Parte(BigDecimal monto, boolean fija) {
    }

    /** @param otras en el mismo orden que se pidieron */
    public record Reparto(BigDecimal miParte, List<BigDecimal> otras) {
    }

    /** El total en partes iguales entre {@code personas} (yo incluido). */
    public static Reparto enPartesIguales(BigDecimal total, int personas) {
        if (personas < 2) {
            throw new JuntadaInvalidaException("Una juntada es de al menos 2 personas");
        }
        List<Parte> otras = new ArrayList<>();
        for (int i = 1; i < personas; i++) {
            otras.add(new Parte(BigDecimal.ZERO, false));
        }
        return repartir(total, otras);
    }

    /**
     * Reparte lo que no está fijo entre las partes libres y la mía.
     *
     * @throws JuntadaInvalidaException si las partes fijas ya suman el total (o más): no quedaría nada para mí
     */
    public static Reparto repartir(BigDecimal total, List<Parte> otras) {
        if (total == null || total.signum() <= 0) {
            throw new JuntadaInvalidaException("El total tiene que ser mayor a cero");
        }
        BigDecimal fijas = otras.stream().filter(Parte::fija).map(Parte::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal resto = total.subtract(fijas);
        long libres = otras.stream().filter(p -> !p.fija()).count();
        // Las libres en partes iguales, a peso y para abajo; lo que sobra del redondeo es mío.
        BigDecimal cadaUna = resto.signum() <= 0 ? BigDecimal.ZERO
                : resto.divide(BigDecimal.valueOf(libres + 1), 0, RoundingMode.DOWN);
        BigDecimal mia = resto.subtract(cadaUna.multiply(BigDecimal.valueOf(libres)));
        if (mia.signum() <= 0) {
            throw new JuntadaInvalidaException("Las partes de los demás suman " + pesos(fijas)
                    + " y el total es " + pesos(total) + ": no queda nada para tu parte");
        }
        List<BigDecimal> nuevas = otras.stream()
                .map(p -> (p.fija() ? p.monto() : cadaUna).setScale(2, RoundingMode.HALF_UP))
                .toList();
        return new Reparto(mia.setScale(2, RoundingMode.HALF_UP), nuevas);
    }

    private static String pesos(BigDecimal monto) {
        return "$" + monto.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
