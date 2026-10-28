package ar.gastito.recibo;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import ar.gastito.movimiento.IngresoService;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.SueldoService;

/**
 * Mes por mes: lo calculado, lo cobrado y lo que dice el recibo, con las diferencias entre los tres. Es la fila de
 * la sección Ingresos del dashboard y de la pregunta {@code recibosSueldo}.
 */
@Service
public class SeguimientoSueldo {

    private final SueldoService sueldo;
    private final IngresoService ingresos;
    private final ReciboService recibos;

    public SeguimientoSueldo(SueldoService sueldo, IngresoService ingresos, ReciboService recibos) {
        this.sueldo = sueldo;
        this.ingresos = ingresos;
        this.recibos = recibos;
    }

    /**
     * Cada dato es null si falta (no hay cálculo, no se cargó el cobro o no llegó el recibo), y las diferencias
     * también.
     *
     * @param cobradoVsCalculado lo cobrado contra lo calculado
     * @param reciboVsCobrado    el neto del recibo contra lo cobrado
     * @param reciboVsCalculado  el neto del recibo contra lo calculado
     * @param avisos             lo raro que encontró el control del recibo (vacío si no hay recibo)
     */
    public record Mes(YearMonth mes, BigDecimal calculado, BigDecimal cobrado, SueldoService.Comparacion cobradoVsCalculado,
            Long reciboId, BigDecimal reciboNeto, SueldoService.Comparacion reciboVsCobrado,
            SueldoService.Comparacion reciboVsCalculado, boolean reciboConArchivo, List<String> avisos,
            boolean reciboSinNadaRaro) {
    }

    /** Los últimos {@code meses} meses hasta {@code hasta} inclusive, del más viejo al más nuevo. */
    public List<Mes> meses(YearMonth hasta, int meses) {
        if (meses < 1) {
            throw new IllegalArgumentException("Hace falta al menos un mes");
        }
        List<Mes> filas = new ArrayList<>();
        for (YearMonth mes = hasta.minusMonths(meses - 1L); !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
            filas.add(mes(mes));
        }
        return filas;
    }

    public Mes mes(YearMonth mes) {
        BigDecimal calculado = sueldo.calculoDelMes(mes).map(CalculoSueldo::getNetoTotal).orElse(null);
        BigDecimal cobrado = Optional.of(ingresos.delMes(mes).sueldo()).filter(s -> s.signum() > 0).orElse(null);
        Optional<ReciboService.VistaRecibo> recibo = recibos.delPeriodo(mes, TipoRecibo.SUELDO_MENSUAL);
        BigDecimal neto = recibo.map(ReciboService.VistaRecibo::neto).orElse(null);
        return new Mes(mes, calculado, cobrado, comparar(mes, calculado, cobrado),
                recibo.map(ReciboService.VistaRecibo::id).orElse(null), neto, comparar(mes, cobrado, neto),
                comparar(mes, calculado, neto), recibo.map(ReciboService.VistaRecibo::conArchivo).orElse(false),
                recibo.map(r -> r.control().hallazgos().stream().map(ControlRecibo.Hallazgo::texto).toList())
                        .orElse(List.of()),
                recibo.map(r -> r.control().sinNadaRaro()).orElse(false));
    }

    /** {@code otro} contra {@code base}; null si falta alguno. */
    private SueldoService.Comparacion comparar(YearMonth mes, BigDecimal base, BigDecimal otro) {
        return base == null || otro == null ? null : sueldo.comparar(mes, base, otro);
    }
}
