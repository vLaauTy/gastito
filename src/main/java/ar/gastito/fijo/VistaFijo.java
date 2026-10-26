package ar.gastito.fijo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un gasto fijo listo para mostrar, armado dentro de la transacción.
 *
 * @param montoPropuesto lo que se va a proponer en el próximo cobro (en los variables, el promedio)
 * @param billetera      la cuenta, la tarjeta o el medio de pago, o null
 * @param proximoCobro   null si está dado de baja
 */
public record VistaFijo(Long id, String nombre, BigDecimal montoEsperado, BigDecimal montoPropuesto,
        String categoria, String emoji, String billetera, int diaDelMes, Frecuencia frecuencia,
        boolean montoVariable, boolean activo, LocalDate proximoCobro) {
}
