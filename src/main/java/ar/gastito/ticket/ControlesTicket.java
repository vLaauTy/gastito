package ar.gastito.ticket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/**
 * Las cuentas de un ticket, en Java (la IA lee, no suma). Con el SUBTOTAL impreso hay tres controles:
 * <ol>
 * <li>Los productos suman el subtotal.</li>
 * <li>Los descuentos suman los descuentos impresos (si el ticket los imprime).</li>
 * <li>El subtotal más los descuentos da el total.</li>
 * </ol>
 * Sin subtotal impreso, el único control posible es productos menos descuentos = total. Cada control acepta una
 * diferencia de hasta {@link #TOLERANCIA} (centavos que no se leen, redondeos).
 */
public final class ControlesTicket {

    public static final BigDecimal TOLERANCIA = new BigDecimal("10");

    public enum Control {
        /** Los productos y el SUBTOTAL impreso. */
        PRODUCTOS_Y_SUBTOTAL,
        /** Los descuentos y el total de descuentos impreso. */
        DESCUENTOS,
        /** El subtotal más los descuentos, y el TOTAL. */
        SUBTOTAL_Y_TOTAL,
        /** Sin subtotal impreso: los productos menos los descuentos, y el TOTAL. */
        PRODUCTOS_Y_TOTAL
    }

    /**
     * Un control que no cierra. Los descuentos van en negativo.
     *
     * @param calculado lo que da la suma de los renglones (o subtotal + descuentos)
     * @param impreso   lo que dice el ticket
     */
    public record Diferencia(Control control, BigDecimal calculado, BigDecimal impreso) {
    }

    private ControlesTicket() {
    }

    /**
     * @param subtotales         los subtotales de cada renglón, con los descuentos en negativo
     * @param subtotalImpreso    el SUBTOTAL del ticket, o null
     * @param descuentosImpresos el total de descuentos impreso (negativo), o null
     * @param total              el TOTAL impreso, que es el monto del gasto
     * @return los controles que no cierran; vacía si las cuentas dan
     */
    public static List<Diferencia> revisar(List<BigDecimal> subtotales, BigDecimal subtotalImpreso,
            BigDecimal descuentosImpresos, BigDecimal total) {
        BigDecimal productos = subtotales.stream().filter(s -> s.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal descuentos = subtotales.stream().filter(s -> s.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Diferencia> diferencias = new ArrayList<>();
        if (subtotalImpreso == null) {
            if (!subtotales.isEmpty() && total != null) {
                comparar(diferencias, Control.PRODUCTOS_Y_TOTAL, productos.add(descuentos), total);
            }
            return diferencias;
        }
        if (!subtotales.isEmpty()) {
            comparar(diferencias, Control.PRODUCTOS_Y_SUBTOTAL, productos, subtotalImpreso);
            if (descuentosImpresos != null) {
                comparar(diferencias, Control.DESCUENTOS, descuentos, descuentosImpresos);
            }
        }
        if (total != null) {
            BigDecimal descuentosDelTicket = descuentosImpresos != null ? descuentosImpresos : descuentos;
            comparar(diferencias, Control.SUBTOTAL_Y_TOTAL, subtotalImpreso.add(descuentosDelTicket), total);
        }
        return diferencias;
    }

    /** Los controles de un ticket ya guardado (o pendiente), con lo que quedó en la base. */
    public static List<Diferencia> revisar(Movimiento m) {
        return revisar(m.getItems().stream().map(MovimientoItem::getSubtotal).toList(), m.getSubtotalImpreso(),
                m.getDescuentosImpresos(), m.getMonto());
    }

    private static void comparar(List<Diferencia> diferencias, Control control, BigDecimal calculado,
            BigDecimal impreso) {
        if (calculado.subtract(impreso).abs().compareTo(TOLERANCIA) > 0) {
            diferencias.add(new Diferencia(control, calculado, impreso));
        }
    }
}
