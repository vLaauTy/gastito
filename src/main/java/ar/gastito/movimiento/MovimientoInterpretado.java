package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lo que se entendió de un mensaje, ya validado contra la base, listo para mostrar y guardar como pendiente.
 * Categoría siempre presente; medio de pago, cuenta y tarjeta pueden ser null.
 */
public record MovimientoInterpretado(
        BigDecimal monto,
        String descripcion,
        Categoria categoria,
        MedioPago medioPago,
        Cuenta cuenta,
        Tarjeta tarjeta,
        LocalDateTime fechaHora) {
}
