package ar.gastito.movimiento;

import java.math.BigDecimal;

/**
 * Un renglón de un ticket ya validado, listo para guardar.
 *
 * @param cantidad       null si el ticket no la dice
 * @param precioUnitario null si el ticket no lo dice
 * @param subtotal       negativo en un descuento
 */
public record ItemTicket(String descripcion, BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
}
