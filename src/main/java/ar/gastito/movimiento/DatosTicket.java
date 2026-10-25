package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que trae un ticket además del gasto, ya validado.
 *
 * @param comercio           como lo leyó la IA, o null
 * @param sucursal           "5601", o null
 * @param subtotalImpreso    el SUBTOTAL del ticket, o null si no figura
 * @param descuentosImpresos el total de descuentos impreso, negativo (o cero), o null si no figura
 * @param lineasIlegibles    las líneas que no se pudieron leer ("el importe de la línea 7 está tapado")
 * @param items              los productos y los descuentos, en el orden impreso
 */
public record DatosTicket(String comercio, String sucursal, BigDecimal subtotalImpreso,
        BigDecimal descuentosImpresos, List<String> lineasIlegibles, List<ItemTicket> items) {

    public DatosTicket {
        lineasIlegibles = lineasIlegibles == null ? List.of() : List.copyOf(lineasIlegibles);
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Solo comercio y productos. */
    public static DatosTicket de(String comercio, List<ItemTicket> items) {
        return new DatosTicket(comercio, null, null, null, List.of(), items);
    }
}
