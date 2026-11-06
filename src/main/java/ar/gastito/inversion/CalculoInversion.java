package ar.gastito.inversion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * El rendimiento de las inversiones, en Java y con BigDecimal. Cada tenencia (la bolsa de una cuenta o una posición)
 * tiene lo que se puso (saldo inicial + aportes − retiros, y las compras y ventas entre la bolsa y las posiciones) y
 * lo que vale (la última valuación; si nunca se valuó, lo que se puso). Rendimiento = valor − lo puesto, en pesos y en
 * % de lo puesto. Es una ganancia o una pérdida, nunca un ingreso.
 */
public final class CalculoInversion {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private CalculoInversion() {
    }

    /** Una tenencia: la bolsa de una cuenta (posicionId null) o una de sus posiciones. */
    public record Clave(long cuentaId, Long posicionId) {
    }

    /** Lo que entra (positivo) o sale (negativo) de lo puesto en una tenencia, en una fecha. */
    public record Aporte(Clave clave, LocalDate fecha, BigDecimal monto) {
    }

    /**
     * @param sinValuar           nunca se cargó un valor: vale lo que se puso
     * @param ultimaValuacion     la fecha de la última valuación de cualquier origen, o null
     * @param ultimaActualizacion la fecha del último valor real (manual o "igual que antes"), o null
     */
    public record Estado(BigDecimal aportado, BigDecimal valor, boolean sinValuar, LocalDate ultimaValuacion,
            LocalDate ultimaActualizacion) {

        public Rendimiento rendimiento() {
            return CalculoInversion.rendimiento(valor, aportado);
        }
    }

    /** @param porcentaje sobre lo puesto, con 2 decimales; null si no se puso nada (o se sacó más de lo puesto) */
    public record Rendimiento(BigDecimal pesos, BigDecimal porcentaje) {

        public boolean esGanancia() {
            return pesos.signum() > 0;
        }

        public boolean esPerdida() {
            return pesos.signum() < 0;
        }
    }

    /** Cómo estaba el total en una fecha: lo puesto hasta ese día y lo que valía. */
    public record Punto(LocalDate fecha, BigDecimal aportado, BigDecimal valor) {
    }

    public static Rendimiento rendimiento(BigDecimal valor, BigDecimal aportado) {
        BigDecimal pesos = pesos(valor.subtract(aportado));
        BigDecimal porcentaje = aportado.signum() > 0
                ? pesos.multiply(CIEN).divide(aportado, 2, RoundingMode.HALF_UP)
                : null;
        return new Rendimiento(pesos, porcentaje);
    }

    /**
     * Una tenencia hasta una fecha (inclusive).
     *
     * @param valuaciones de la más vieja a la más nueva (la última de cada día manda)
     */
    public static Estado estado(Clave clave, Collection<Aporte> aportes,
            List<FuenteDeValuaciones.Valuacion> valuaciones, LocalDate hasta) {
        BigDecimal aportado = aportes.stream()
                .filter(a -> a.clave().equals(clave) && !a.fecha().isAfter(hasta))
                .map(Aporte::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        FuenteDeValuaciones.Valuacion ultima = null;
        LocalDate actualizacion = null;
        for (FuenteDeValuaciones.Valuacion v : valuaciones) {
            if (!esDe(v, clave) || v.fecha().isAfter(hasta)) {
                continue;
            }
            if (ultima == null || !v.fecha().isBefore(ultima.fecha())) {
                ultima = v;
            }
            if (v.origen().esActualizacion() && (actualizacion == null || v.fecha().isAfter(actualizacion))) {
                actualizacion = v.fecha();
            }
        }
        return ultima == null
                ? new Estado(pesos(aportado), pesos(aportado), true, null, null)
                : new Estado(pesos(aportado), pesos(ultima.valor()), false, ultima.fecha(), actualizacion);
    }

    /**
     * El total de varias tenencias en cada fecha con un aporte o una valuación: para el gráfico del valor en el
     * tiempo contra lo aportado.
     */
    public static List<Punto> serie(Collection<Clave> claves, Collection<Aporte> aportes,
            List<FuenteDeValuaciones.Valuacion> valuaciones) {
        TreeSet<LocalDate> fechas = new TreeSet<>();
        Stream.concat(aportes.stream().filter(a -> claves.contains(a.clave())).map(Aporte::fecha),
                valuaciones.stream().filter(v -> claves.stream().anyMatch(c -> esDe(v, c)))
                        .map(FuenteDeValuaciones.Valuacion::fecha))
                .forEach(fechas::add);
        return fechas.stream().map(fecha -> {
            BigDecimal aportado = BigDecimal.ZERO;
            BigDecimal valor = BigDecimal.ZERO;
            for (Clave c : claves) {
                Estado e = estado(c, aportes, valuaciones, fecha);
                aportado = aportado.add(e.aportado());
                valor = valor.add(e.valor());
            }
            return new Punto(fecha, pesos(aportado), pesos(valor));
        }).toList();
    }

    static boolean esDe(FuenteDeValuaciones.Valuacion v, Clave clave) {
        return v.cuentaId() == clave.cuentaId() && Objects.equals(v.posicionId(), clave.posicionId());
    }

    static BigDecimal pesos(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP);
    }
}
