package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Cuándo dos movimientos son muy parecidos (probablemente la misma compra cargada dos veces):
 * <ul>
 * <li>el mismo monto, con una tolerancia del 1% o $50, lo que sea mayor;</li>
 * <li>a no más de {@code ventana} de distancia (null: no se mira la hora, como con el cobro de un fijo del mes);</li>
 * <li>y, entre los datos que tienen los dos, la misma categoría o el mismo comercio. Si los dos tienen categoría y
 * es distinta, pero el comercio coincide, cuenta igual: un ticket puede quedar en otra categoría que el audio.</li>
 * </ul>
 */
public final class ReglaRepetidos {

    static final BigDecimal TOLERANCIA_PORCENTAJE = new BigDecimal("0.01");
    static final BigDecimal TOLERANCIA_MINIMA = new BigDecimal("50");

    private ReglaRepetidos() {
    }

    public static boolean parecidos(Huella a, Huella b, Duration ventana) {
        return montosParecidos(a.monto(), b.monto()) && cerca(a, b, ventana) && mismaCompra(a, b);
    }

    static boolean montosParecidos(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        BigDecimal tolerancia = a.max(b).multiply(TOLERANCIA_PORCENTAJE).max(TOLERANCIA_MINIMA);
        return a.subtract(b).abs().compareTo(tolerancia) <= 0;
    }

    private static boolean cerca(Huella a, Huella b, Duration ventana) {
        if (ventana == null) {
            return true;
        }
        return Duration.between(a.fechaHora(), b.fechaHora()).abs().compareTo(ventana) <= 0;
    }

    private static boolean mismaCompra(Huella a, Huella b) {
        boolean comparanCategoria = a.categoriaId() != null && b.categoriaId() != null;
        String comercioA = a.comercioNormalizado();
        String comercioB = b.comercioNormalizado();
        boolean comparanComercio = comercioA != null && comercioB != null;
        if (!comparanCategoria && !comparanComercio) {
            return true;
        }
        return (comparanCategoria && Objects.equals(a.categoriaId(), b.categoriaId()))
                || (comparanComercio && comercioA.equals(comercioB));
    }
}
