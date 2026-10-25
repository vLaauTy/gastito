package ar.gastito.movimiento;

import java.math.BigDecimal;

/**
 * Un renglón de un ticket ya validado, listo para guardar.
 *
 * @param descripcion         legible ("Galletitas cracker queso")
 * @param descripcionOriginal como está impresa ("GALLET CRACKER QSO"), o null
 * @param codigoBarras        EAN-8 o EAN-13 con dígito verificador correcto, o null
 * @param cantidad            null si el ticket no la dice
 * @param precioUnitario      null si el ticket no lo dice
 * @param subtotal            negativo en un descuento
 */
public record ItemTicket(String descripcion, String descripcionOriginal, String codigoBarras, BigDecimal cantidad,
        BigDecimal precioUnitario, BigDecimal subtotal) {

    /** Sin descripción original ni código de barras. */
    public ItemTicket(String descripcion, BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
        this(descripcion, null, null, cantidad, precioUnitario, subtotal);
    }
}
