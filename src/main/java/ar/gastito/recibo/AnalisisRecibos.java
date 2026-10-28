package ar.gastito.recibo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Los números de los recibos de sueldo: la serie mensual y las variaciones del dashboard, y el resumen de la
 * herramienta de preguntas. Se calculan acá, nunca en el navegador ni en la IA. La serie y las variaciones usan
 * solo los sueldos mensuales: el aguinaldo y las liquidaciones no entran, para no inflar la comparación.
 */
@Service
public class AnalisisRecibos {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final int MAXIMO_MESES_SEGUIMIENTO = 24;

    private final ReciboService recibos;
    private final SeguimientoSueldo seguimiento;

    public AnalisisRecibos(ReciboService recibos, SeguimientoSueldo seguimiento) {
        this.recibos = recibos;
        this.seguimiento = seguimiento;
    }

    // --- Dashboard ---

    /** Un mes de la serie; sin recibo, los importes son null. */
    public record PuntoSerie(YearMonth mes, boolean conRecibo, BigDecimal bruto, BigDecimal descuentos,
            BigDecimal neto) {
    }

    /**
     * El neto contra el de otro mes.
     *
     * @param variacionPorcentaje entero; null si no hay con qué comparar o el otro mes cobró $0
     * @param direccion           SUBE, BAJA, IGUAL o SIN_DATOS (el otro mes no tiene recibo)
     */
    public record Cambio(YearMonth mes, BigDecimal neto, BigDecimal diferencia, Integer variacionPorcentaje,
            String direccion) {
    }

    /**
     * @param netoActual el neto del último mes de la serie; null si ese mes no tiene recibo
     * @param contraMesAnterior          siempre presente; SIN_DATOS si falta algún recibo
     * @param contraMismoMesAnioAnterior idem
     */
    public record Serie(YearMonth hasta, List<PuntoSerie> meses, BigDecimal netoActual, Cambio contraMesAnterior,
            Cambio contraMismoMesAnioAnterior) {
    }

    /** Los últimos {@code meses} meses hasta {@code hasta} inclusive, del más viejo al más nuevo, con los vacíos. */
    public Serie serie(YearMonth hasta, int meses) {
        if (meses < 1) {
            throw new IllegalArgumentException("Hace falta al menos un mes");
        }
        YearMonth desde = hasta.minusMonths(meses - 1L);
        // Para las variaciones hace falta también el mismo mes del año anterior.
        YearMonth masViejo = desde.isBefore(hasta.minusYears(1)) ? desde : hasta.minusYears(1);
        Map<YearMonth, BigDecimal[]> porMes = sueldosPorMes(masViejo, hasta);

        List<PuntoSerie> puntos = new ArrayList<>();
        for (YearMonth mes = desde; !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
            BigDecimal[] t = porMes.get(mes);
            puntos.add(t == null ? new PuntoSerie(mes, false, null, null, null)
                    : new PuntoSerie(mes, true, t[0], t[1], t[2]));
        }
        BigDecimal[] actual = porMes.get(hasta);
        BigDecimal netoActual = actual == null ? null : actual[2];
        return new Serie(hasta, puntos, netoActual, cambio(hasta, netoActual, hasta.minusMonths(1), porMes),
                cambio(hasta, netoActual, hasta.minusYears(1), porMes));
    }

    private static Cambio cambio(YearMonth mes, BigDecimal netoActual, YearMonth otro,
            Map<YearMonth, BigDecimal[]> porMes) {
        BigDecimal[] anterior = porMes.get(otro);
        if (netoActual == null || anterior == null) {
            return new Cambio(otro, anterior == null ? null : anterior[2], null, null, "SIN_DATOS");
        }
        return comparar(otro, anterior[2], netoActual);
    }

    private static Cambio comparar(YearMonth otro, BigDecimal antes, BigDecimal ahora) {
        BigDecimal diferencia = ahora.subtract(antes);
        Integer porcentaje = antes.signum() == 0 ? null
                : diferencia.multiply(CIEN).divide(antes, 0, RoundingMode.HALF_UP).intValue();
        int signo = diferencia.signum();
        return new Cambio(otro, antes, diferencia, porcentaje, signo > 0 ? "SUBE" : signo < 0 ? "BAJA" : "IGUAL");
    }

    /** Bruto, descuentos y neto de los sueldos mensuales de cada mes (si hay dos en un mes, se suman). */
    private Map<YearMonth, BigDecimal[]> sueldosPorMes(YearMonth desde, YearMonth hasta) {
        Map<YearMonth, BigDecimal[]> porMes = new LinkedHashMap<>();
        for (ReciboService.VistaRecibo r : recibos.entre(desde, hasta)) {
            if (r.tipo() != TipoRecibo.SUELDO_MENSUAL) {
                continue;
            }
            BigDecimal[] t = porMes.computeIfAbsent(r.periodo(),
                    m -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(r.bruto());
            t[1] = t[1].add(r.descuentos());
            t[2] = t[2].add(r.neto());
        }
        return porMes;
    }

    // --- Preguntas ---

    public record ReciboResumen(YearMonth periodo, String empleador, String tipo, BigDecimal bruto,
            BigDecimal descuentos, BigDecimal neto, List<String> cosasRaras) {
    }

    /** Lo descontado por un mismo concepto en todos los recibos del rango, por ejemplo el impuesto a las ganancias. */
    public record DescuentoTotal(String concepto, BigDecimal total) {
    }

    /**
     * @param primerSueldo  el neto del primer sueldo mensual del rango, o null si no hubo
     * @param ultimoSueldo  el del último
     * @param aumentoNeto   el último contra el primero; null si hay menos de dos sueldos mensuales
     */
    public record ResumenRecibos(LocalDate desde, LocalDate hasta, int cantidad, List<ReciboResumen> recibos,
            BigDecimal totalBruto, BigDecimal totalDescuentos, BigDecimal totalNeto,
            List<DescuentoTotal> descuentosPorConcepto, Cambio aumentoNeto, List<SeguimientoSueldo.Mes> sueldoMesAMes) {
    }

    /** Los recibos cuyo período cae entre los dos días (los meses de {@code desde} y {@code hasta}, completos). */
    public ResumenRecibos resumen(LocalDate desde, LocalDate hasta) {
        List<ReciboService.VistaRecibo> lista = recibos.entre(YearMonth.from(desde), YearMonth.from(hasta));
        List<ReciboResumen> resumenes = lista.stream()
                .map(r -> new ReciboResumen(r.periodo(), r.empleador(), r.tipo().getNombre(), r.bruto(),
                        r.descuentos(), r.neto(), r.control().hallazgos().stream().map(ControlRecibo.Hallazgo::texto).toList()))
                .toList();
        BigDecimal bruto = BigDecimal.ZERO;
        BigDecimal descuentos = BigDecimal.ZERO;
        BigDecimal neto = BigDecimal.ZERO;
        Map<String, DescuentoAcumulado> conceptos = new LinkedHashMap<>();
        for (ReciboService.VistaRecibo r : lista) {
            bruto = bruto.add(r.bruto());
            descuentos = descuentos.add(r.descuentos());
            neto = neto.add(r.neto());
            for (ReciboService.Renglon c : r.conceptos()) {
                if (c.grupo() == GrupoConcepto.DESCUENTO) {
                    DescuentoAcumulado d = conceptos.computeIfAbsent(claveDeConcepto(c.descripcion()),
                            k -> new DescuentoAcumulado());
                    d.nombre = c.descripcion();
                    d.total = d.total.add(c.importe());
                }
            }
        }
        List<DescuentoTotal> porConcepto = conceptos.values().stream()
                .map(d -> new DescuentoTotal(d.nombre, d.total))
                .sorted((a, b) -> b.total().compareTo(a.total()))
                .toList();

        List<ReciboService.VistaRecibo> sueldos = lista.stream()
                .filter(r -> r.tipo() == TipoRecibo.SUELDO_MENSUAL).toList();
        Cambio aumento = null;
        if (sueldos.size() >= 2) {
            ReciboService.VistaRecibo primero = sueldos.get(0);
            ReciboService.VistaRecibo ultimo = sueldos.get(sueldos.size() - 1);
            aumento = comparar(primero.periodo(), primero.neto(), ultimo.neto());
        }
        return new ResumenRecibos(desde, hasta, lista.size(), resumenes, bruto, descuentos, neto, porConcepto,
                aumento, sueldoMesAMes(desde, hasta));
    }

    /** Los meses del rango con algún dato: lo calculado, lo cobrado y el recibo, con las diferencias. */
    private List<SeguimientoSueldo.Mes> sueldoMesAMes(LocalDate desde, LocalDate hasta) {
        List<SeguimientoSueldo.Mes> meses = new ArrayList<>();
        YearMonth fin = YearMonth.from(hasta);
        for (YearMonth mes = YearMonth.from(desde); !mes.isAfter(fin) && meses.size() < MAXIMO_MESES_SEGUIMIENTO;
                mes = mes.plusMonths(1)) {
            SeguimientoSueldo.Mes m = seguimiento.mes(mes);
            if (m.calculado() != null || m.cobrado() != null || m.reciboId() != null) {
                meses.add(m);
            }
        }
        return meses;
    }

    private static final class DescuentoAcumulado {
        String nombre;
        BigDecimal total = BigDecimal.ZERO;
    }

    /** "Jubilación 11%" y "JUBILACION 11 %" son el mismo concepto: sin tildes, mayúsculas, números ni signos. */
    static String claveDeConcepto(String descripcion) {
        String sinTildes = Normalizer.normalize(descripcion, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").strip();
    }
}
