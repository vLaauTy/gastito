package ar.gastito.movimiento;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** Rango de fechas [desde, hasta). */
public record Periodo(LocalDateTime desde, LocalDateTime hasta) {

    public static Periodo delDia(LocalDate dia) {
        return new Periodo(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay());
    }

    public static Periodo delMes(YearMonth mes) {
        return new Periodo(mes.atDay(1).atStartOfDay(), mes.plusMonths(1).atDay(1).atStartOfDay());
    }
}
