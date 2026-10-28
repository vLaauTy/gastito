package ar.gastito.recibo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalisisRecibosTest {

    private final ReciboService servicio = mock(ReciboService.class);
    private final AnalisisRecibos analisis = new AnalisisRecibos(servicio);
    private final List<ReciboService.VistaRecibo> guardados = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(servicio.guardadosEntre(any(), any())).thenAnswer(inv -> {
            YearMonth desde = inv.getArgument(0);
            YearMonth hasta = inv.getArgument(1);
            return guardados.stream().filter(r -> !r.periodo().isBefore(desde) && !r.periodo().isAfter(hasta))
                    .toList();
        });
    }

    /** Un sueldo mensual: bruto, un descuento del 20 % y un impuesto a las ganancias de $10.000. */
    private void sueldo(YearMonth mes, String bruto) {
        recibo(mes, TipoRecibo.SUELDO_MENSUAL, bruto, "Impuesto a las ganancias", "10000");
    }

    private void recibo(YearMonth mes, TipoRecibo tipo, String bruto, String concepto, String importeConcepto) {
        BigDecimal b = new BigDecimal(bruto);
        BigDecimal jubilacion = b.multiply(new BigDecimal("0.11")).setScale(2);
        BigDecimal ganancias = new BigDecimal(importeConcepto);
        BigDecimal descuentos = jubilacion.add(ganancias);
        guardados.add(new ReciboService.VistaRecibo((long) guardados.size() + 1, (long) guardados.size() + 100,
                "Empresa Ejemplo", mes, tipo, null, b, descuentos, b.subtract(descuentos),
                List.of(new ReciboService.Renglon("Sueldo básico", GrupoConcepto.REMUNERATIVO, b),
                        new ReciboService.Renglon("Jubilación 11%", GrupoConcepto.DESCUENTO, jubilacion),
                        new ReciboService.Renglon(concepto, GrupoConcepto.DESCUENTO, ganancias)),
                null, true));
    }

    @Test
    void laSerieTrae12MesesConHuecosYSinElAguinaldo() {
        sueldo(YearMonth.of(2026, 9), "1000000");
        sueldo(YearMonth.of(2026, 10), "1100000");
        recibo(YearMonth.of(2026, 6), TipoRecibo.AGUINALDO, "500000", "Ganancias", "5000");

        AnalisisRecibos.Serie serie = analisis.serie(YearMonth.of(2026, 10), 12);

        assertThat(serie.meses()).hasSize(12);
        assertThat(serie.meses().get(0).mes()).isEqualTo(YearMonth.of(2025, 11));
        assertThat(serie.meses().get(11).mes()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(serie.meses()).filteredOn(AnalisisRecibos.PuntoSerie::conRecibo).extracting(
                AnalisisRecibos.PuntoSerie::mes).containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        AnalisisRecibos.PuntoSerie octubre = serie.meses().get(11);
        assertThat(octubre.bruto()).isEqualByComparingTo("1100000");
        assertThat(octubre.descuentos()).isEqualByComparingTo("131000");
        assertThat(octubre.neto()).isEqualByComparingTo("969000");
        assertThat(serie.meses().get(7).conRecibo()).isFalse();
        assertThat(serie.meses().get(7).neto()).isNull();
    }

    @Test
    void laVariacionContraElMesAnterior() {
        sueldo(YearMonth.of(2026, 9), "1000000");
        sueldo(YearMonth.of(2026, 10), "1100000");

        AnalisisRecibos.Serie serie = analisis.serie(YearMonth.of(2026, 10), 12);

        assertThat(serie.netoActual()).isEqualByComparingTo("969000");
        AnalisisRecibos.Cambio c = serie.contraMesAnterior();
        assertThat(c.mes()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(c.neto()).isEqualByComparingTo("880000");
        assertThat(c.diferencia()).isEqualByComparingTo("89000");
        assertThat(c.variacionPorcentaje()).isEqualTo(10);
        assertThat(c.direccion()).isEqualTo("SUBE");
    }

    @Test
    void laVariacionContraElMismoMesDelAnioAnterior() {
        sueldo(YearMonth.of(2025, 10), "1000000");
        sueldo(YearMonth.of(2026, 10), "900000");

        AnalisisRecibos.Cambio c = analisis.serie(YearMonth.of(2026, 10), 12).contraMismoMesAnioAnterior();

        assertThat(c.mes()).isEqualTo(YearMonth.of(2025, 10));
        assertThat(c.direccion()).isEqualTo("BAJA");
        assertThat(c.diferencia()).isEqualByComparingTo("-89000");
        assertThat(c.variacionPorcentaje()).isEqualTo(-10);
    }

    @Test
    void sinReciboEnElMesDeComparacionNoHayDatos() {
        sueldo(YearMonth.of(2026, 10), "1100000");

        AnalisisRecibos.Serie serie = analisis.serie(YearMonth.of(2026, 10), 12);

        assertThat(serie.contraMesAnterior().direccion()).isEqualTo("SIN_DATOS");
        assertThat(serie.contraMesAnterior().variacionPorcentaje()).isNull();
        assertThat(serie.contraMesAnterior().neto()).isNull();
        assertThat(serie.contraMismoMesAnioAnterior().direccion()).isEqualTo("SIN_DATOS");
    }

    @Test
    void sinReciboEnElMesActualTampocoSeCompara() {
        sueldo(YearMonth.of(2026, 9), "1000000");

        AnalisisRecibos.Serie serie = analisis.serie(YearMonth.of(2026, 10), 12);

        assertThat(serie.netoActual()).isNull();
        assertThat(serie.contraMesAnterior().direccion()).isEqualTo("SIN_DATOS");
        assertThat(serie.contraMesAnterior().neto()).isEqualByComparingTo("880000");
    }

    @Test
    void igualNetoEsIgual() {
        sueldo(YearMonth.of(2026, 9), "1000000");
        sueldo(YearMonth.of(2026, 10), "1000000");

        AnalisisRecibos.Cambio c = analisis.serie(YearMonth.of(2026, 10), 3).contraMesAnterior();

        assertThat(c.direccion()).isEqualTo("IGUAL");
        assertThat(c.variacionPorcentaje()).isZero();
    }

    // --- Para las preguntas ---

    @Test
    void elResumenSumaLoDescontadoPorConceptoEnTodoElRango() {
        sueldo(YearMonth.of(2026, 1), "1000000");
        sueldo(YearMonth.of(2026, 2), "1000000");
        recibo(YearMonth.of(2026, 3), TipoRecibo.SUELDO_MENSUAL, "1000000", "IMPUESTO A LAS GANANCIAS", "20000");
        sueldo(YearMonth.of(2025, 12), "900000");

        AnalisisRecibos.ResumenRecibos r = analisis.resumen(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 25));

        assertThat(r.cantidad()).isEqualTo(3);
        assertThat(r.totalBruto()).isEqualByComparingTo("3000000");
        assertThat(r.descuentosPorConcepto()).extracting(AnalisisRecibos.DescuentoTotal::concepto,
                d -> d.total().intValue()).contains(
                        org.assertj.core.groups.Tuple.tuple("Jubilación 11%", 330000),
                        org.assertj.core.groups.Tuple.tuple("IMPUESTO A LAS GANANCIAS", 40000));
        assertThat(r.descuentosPorConcepto()).hasSize(2);
        assertThat(r.descuentosPorConcepto().get(0).concepto()).isEqualTo("Jubilación 11%");
    }

    @Test
    void elAumentoEsDelPrimerAlUltimoSueldoMensual() {
        sueldo(YearMonth.of(2026, 1), "1000000");
        recibo(YearMonth.of(2026, 6), TipoRecibo.AGUINALDO, "2000000", "Ganancias", "1");
        sueldo(YearMonth.of(2026, 10), "1200000");

        AnalisisRecibos.Cambio aumento = analisis.resumen(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 25))
                .aumentoNeto();

        assertThat(aumento.mes()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(aumento.neto()).isEqualByComparingTo("880000");
        assertThat(aumento.diferencia()).isEqualByComparingTo("178000");
        assertThat(aumento.variacionPorcentaje()).isEqualTo(20);
        assertThat(aumento.direccion()).isEqualTo("SUBE");
    }

    @Test
    void conUnSoloSueldoNoHayAumento() {
        sueldo(YearMonth.of(2026, 10), "1200000");

        assertThat(analisis.resumen(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 25)).aumentoNeto()).isNull();
    }

    @Test
    void sinRecibosElResumenEstaVacio() {
        AnalisisRecibos.ResumenRecibos r = analisis.resumen(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 25));

        assertThat(r.cantidad()).isZero();
        assertThat(r.recibos()).isEmpty();
        assertThat(r.totalNeto()).isEqualByComparingTo("0");
        assertThat(r.aumentoNeto()).isNull();
    }

    @Test
    void elMismoConceptoEscritoDistintoSeAgrupa() {
        assertThat(AnalisisRecibos.claveDeConcepto("Jubilación 11%")).isEqualTo(
                AnalisisRecibos.claveDeConcepto("JUBILACION  11 %"));
        assertThat(AnalisisRecibos.claveDeConcepto("Ley 19.032 3%")).isEqualTo(
                AnalisisRecibos.claveDeConcepto("LEY 19032 3,00%"));
    }
}
