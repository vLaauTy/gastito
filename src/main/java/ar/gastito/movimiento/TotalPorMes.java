package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.YearMonth;

/** Un total de un mes, tal como sale de la consulta agrupada por año y mes. */
public record TotalPorMes(int anio, int mes, BigDecimal total) {

    public YearMonth yearMonth() {
        return YearMonth.of(anio, mes);
    }
}
