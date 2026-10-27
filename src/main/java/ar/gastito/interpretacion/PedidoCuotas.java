package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * "zapatillas 84000 en 6 cuotas" (o "6 cuotas de 14000"): cuántas cuotas y cuánto, ya resuelto por Java (la IA no
 * calcula). El valor de cada cuota se redondea a 2 decimales.
 */
public record PedidoCuotas(int cantidad, BigDecimal total, BigDecimal valorCuota) {

    static final int MAXIMO_CUOTAS = 24;

    /**
     * Null si no es un plan creíble: sin cantidad válida (entre 2 y {@value #MAXIMO_CUOTAS}), o sin forma de saber
     * el total (ni el total ni el valor de cada cuota).
     *
     * @param montoTotal el total ya interpretado del mensaje (si lo dijo), o null
     */
    static PedidoCuotas de(String cantidadIa, String valorCuotaIa, BigDecimal montoTotal) {
        if (cantidadIa == null || !cantidadIa.strip().matches("\\d{1,2}")) {
            return null;
        }
        int cantidad = Integer.parseInt(cantidadIa.strip());
        if (cantidad < 2 || cantidad > MAXIMO_CUOTAS) {
            return null;
        }
        BigDecimal valorDicho = ValidadorInterpretacion.monto(valorCuotaIa).orElse(null);
        BigDecimal total = montoTotal != null ? montoTotal
                : valorDicho != null ? valorDicho.multiply(BigDecimal.valueOf(cantidad)) : null;
        if (total == null) {
            return null;
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        BigDecimal valorCuota = total.divide(BigDecimal.valueOf(cantidad), 2, RoundingMode.HALF_UP);
        return new PedidoCuotas(cantidad, total, valorCuota);
    }
}
