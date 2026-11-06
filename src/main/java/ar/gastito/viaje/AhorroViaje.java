package ar.gastito.viaje;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Cuánto separar por mes para lo que falta pagar del viaje (regla 2: la cuenta la hace Java). Lo que falta, menos lo
 * reservado en las inversiones para el viaje, dividido por los cobros de sueldo que quedan antes de que empiece.
 *
 * @param falta     lo que falta pagar con monto (lo que no tiene monto no cuenta)
 * @param reservado lo apartado en inversiones para el viaje
 * @param aSeparar  lo que falta menos lo reservado (cero si alcanza)
 * @param cobros    cuántos sueldos se cobran desde hoy hasta el día antes del viaje
 * @param porMes    lo que conviene separar de cada sueldo (redondeado a peso, para arriba), o null si no quedan
 *                  cobros
 */
public record AhorroViaje(BigDecimal falta, BigDecimal reservado, BigDecimal aSeparar, int cobros,
        BigDecimal porMes) {

    /**
     * @param diaDeCobro el día del mes en que se cobra el sueldo (el del último cobro)
     * @param yaCobradoEsteMes si el sueldo de este mes ya entró (no cuenta otra vez)
     */
    public static AhorroViaje calcular(BigDecimal falta, BigDecimal reservado, LocalDate hoy, LocalDate inicio,
            int diaDeCobro, boolean yaCobradoEsteMes) {
        BigDecimal aSeparar = falta.subtract(reservado).max(BigDecimal.ZERO);
        int cobros = cobros(hoy, inicio, diaDeCobro, yaCobradoEsteMes);
        BigDecimal porMes = cobros == 0 ? null
                : aSeparar.divide(BigDecimal.valueOf(cobros), 0, RoundingMode.CEILING).setScale(2);
        return new AhorroViaje(falta, reservado, aSeparar, cobros, porMes);
    }

    /** Los cobros de sueldo entre hoy (incluido, si todavía no entró) y el día antes del viaje. */
    static int cobros(LocalDate hoy, LocalDate inicio, int diaDeCobro, boolean yaCobradoEsteMes) {
        int cobros = 0;
        for (YearMonth mes = YearMonth.from(hoy); !mes.atDay(1).isAfter(inicio); mes = mes.plusMonths(1)) {
            LocalDate cobro = mes.atDay(Math.min(diaDeCobro, mes.lengthOfMonth()));
            // El de este mes cuenta si todavía no entró (aunque se haya pasado el día); los demás, siempre.
            boolean esteMes = mes.equals(YearMonth.from(hoy));
            if (cobro.isBefore(inicio) && (esteMes ? !yaCobradoEsteMes : !cobro.isBefore(hoy))) {
                cobros++;
            }
        }
        return cobros;
    }
}
