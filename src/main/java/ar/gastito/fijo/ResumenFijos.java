package ar.gastito.fijo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.fijo.GastoFijoService.EstadoChecklist;
import ar.gastito.fijo.GastoFijoService.ItemChecklist;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Periodo;

/**
 * Cuánto se va en fijos en un mes, para arriba del checklist (dashboard y botón "Fijos" del bot): lo pagado (con su
 * monto, real o estimado) más lo que falta (con su monto de referencia), y, sumando las cuotas del mes, lo
 * comprometido y qué parte es de los ingresos. Salen del checklist, así que no cuentan los "este mes no" ni los
 * bimestrales que no tocan ese mes.
 */
@Service
public class ResumenFijos {

    private final GastoFijoService fijos;
    private final GastoFijoRepository repositorio;
    private final MovimientoRepository movimientos;

    public ResumenFijos(GastoFijoService fijos, GastoFijoRepository repositorio, MovimientoRepository movimientos) {
        this.fijos = fijos;
        this.repositorio = repositorio;
        this.movimientos = movimientos;
    }

    /**
     * @param estimado             pagado + falta
     * @param falta                los que faltan, con su monto de referencia (los que no tienen, no suman)
     * @param sinMonto             cuántos de los que faltan no tienen monto de referencia
     * @param cuotas               las cuotas con fecha en el mes
     * @param comprometido         estimado + cuotas
     * @param ingresos             los ingresos cobrados en el mes, o null si no hay
     * @param porcentajeIngresos   comprometido sobre ingresos, redondeado; null sin ingresos
     */
    public record ResumenDelMes(BigDecimal estimado, BigDecimal pagado, BigDecimal falta, int pagados, int faltan,
            int sinMonto, BigDecimal cuotas, int cantidadCuotas, BigDecimal comprometido, BigDecimal ingresos,
            Integer porcentajeIngresos) {
    }

    @Transactional(readOnly = true)
    public ResumenDelMes delMes(YearMonth mes) {
        return delMes(mes, fijos.checklist(mes));
    }

    /** Con el checklist ya armado (el endpoint lo devuelve también). */
    @Transactional(readOnly = true)
    public ResumenDelMes delMes(YearMonth mes, List<ItemChecklist> items) {
        BigDecimal pagado = BigDecimal.ZERO;
        BigDecimal falta = BigDecimal.ZERO;
        int pagados = 0;
        int faltan = 0;
        int sinMonto = 0;
        Map<Long, GastoFijo> porId = repositorio.findAllById(items.stream().map(ItemChecklist::fijoId).toList())
                .stream().collect(Collectors.toMap(GastoFijo::getId, Function.identity()));
        for (ItemChecklist item : items) {
            if (item.estado() == EstadoChecklist.CARGADO) {
                pagado = pagado.add(item.monto());
                pagados++;
            } else if (item.estado() == EstadoChecklist.FALTA) {
                faltan++;
                BigDecimal referencia = referencia(porId.get(item.fijoId()), mes);
                if (referencia == null) {
                    sinMonto++;
                } else {
                    falta = falta.add(referencia);
                }
            }
        }
        Periodo periodo = Periodo.delMes(mes);
        BigDecimal cuotas = movimientos.totalCuotas(periodo.desde(), periodo.hasta());
        int cantidadCuotas = (int) movimientos.cantidadCuotas(periodo.desde(), periodo.hasta());
        BigDecimal estimado = pagado.add(falta);
        BigDecimal comprometido = estimado.add(cuotas);
        BigDecimal ingresos = movimientos.totalIngresos(periodo.desde(), periodo.hasta());
        boolean hayIngresos = ingresos.signum() > 0;
        return new ResumenDelMes(plata(estimado), plata(pagado), plata(falta), pagados, faltan, sinMonto,
                plata(cuotas), cantidadCuotas, plata(comprometido), hayIngresos ? plata(ingresos) : null,
                hayIngresos ? comprometido.multiply(BigDecimal.valueOf(100)).divide(ingresos, 0, RoundingMode.HALF_UP)
                        .intValue() : null);
    }

    /**
     * El monto de referencia de un fijo que falta: el aproximado del fijo o, si es variable, el promedio de los
     * últimos {@value GastoFijoService#COBROS_PARA_PROMEDIAR} cobros anteriores a ese mes. Null si no hay ninguno.
     */
    BigDecimal referencia(GastoFijo fijo, YearMonth mes) {
        if (fijo == null) {
            return null;
        }
        if (!fijo.isMontoVariable()) {
            return fijo.getMontoEsperado();
        }
        List<BigDecimal> ultimos = movimientos.cobrosConfirmados(fijo.getId(), PageRequest.of(0, 12)).stream()
                .filter(c -> c.getFijoPeriodo().isBefore(mes.atDay(1)))
                .limit(GastoFijoService.COBROS_PARA_PROMEDIAR)
                .map(Movimiento::getMonto)
                .toList();
        if (ultimos.isEmpty()) {
            return fijo.getMontoEsperado();
        }
        return ultimos.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(ultimos.size()), 0, RoundingMode.HALF_UP).setScale(2);
    }

    private static BigDecimal plata(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP);
    }
}
