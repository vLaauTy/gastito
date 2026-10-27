package ar.gastito.cuota;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un plan de cuotas listo para mostrar, armado dentro de la transacción.
 *
 * @param cuotasPagadas   las que ya tienen fecha hasta hoy (ya ocurrieron)
 * @param cuotasRestantes las que todavía no
 * @param proximaCuota    null si no quedan
 */
public record VistaPlanCuotas(Long id, String descripcion, BigDecimal total, int cantidad, BigDecimal valorCuota,
        String categoria, String emoji, String tarjeta, int cuotasPagadas, int cuotasRestantes,
        LocalDate proximaCuota, boolean activo) {
}
