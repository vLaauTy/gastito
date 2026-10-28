package ar.gastito.recibo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.SueldoProperties;
import ar.gastito.sueldo.TipoConceptoSueldo;

/** Cada control del recibo por separado, con valores inventados. */
class ControlReciboTest {

    static final ConceptoSueldo BASICO = concepto(1, "Sueldo básico", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo PRESENTISMO = concepto(2, "Presentismo", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo AL_50 = concepto(5, "Horas al 50%", TipoConceptoSueldo.HORAS, 3, 2);
    static final ConceptoSueldo VIATICO = concepto(8, "Viático", TipoConceptoSueldo.NO_REMUNERATIVO, null, null);
    static final ConceptoSueldo JUBILACION = concepto(10, "Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo LEY = concepto(12, "Ley 19.032", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);

    final ControlRecibo control = new ControlRecibo(
            new SueldoProperties(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("0.05"), 15));

    /** 10 horas al 50% a $3.000: básico 500.000 + presentismo 100.000 + horas 45.000 = bruto 645.000. */
    final CalculoSueldo calculo = calculo();

    static ConceptoSueldo concepto(long id, String nombre, TipoConceptoSueldo tipo, Integer num, Integer den) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, tipo, num, den, (int) id));
    }

    static CalculoSueldo calculo() {
        return calculo(new BigDecimal("10"));
    }

    static CalculoSueldo calculo(BigDecimal horasAl50) {
        EscalaSueldo escala = new EscalaSueldo(LocalDate.of(2026, 1, 1), new BigDecimal("3000"), Map.of(
                BASICO.getId(), new BigDecimal("500000"), PRESENTISMO.getId(), new BigDecimal("100000"),
                VIATICO.getId(), new BigDecimal("300000"), JUBILACION.getId(), new BigDecimal("11"),
                LEY.getId(), new BigDecimal("3")));
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(
                List.of(BASICO, PRESENTISMO, AL_50, VIATICO, JUBILACION, LEY), escala,
                Map.of(AL_50.getId(), horasAl50), Map.of());
        return new CalculoSueldo(YearMonth.of(2026, 10), 1L, r, LocalDateTime.of(2026, 10, 31, 10, 0));
    }

    private static ReciboService.Renglon renglon(String descripcion, GrupoConcepto grupo, String importe,
            ConceptoSueldo concepto) {
        return new ReciboService.Renglon(descripcion, grupo, new BigDecimal(importe),
                concepto == null ? null : concepto.getId());
    }

    /**
     * Un recibo con los descuentos que corresponden a lo que paga: jubilación al 11% y ley 19.032 al porcentaje
     * dado, sobre el total remunerativo. Un importe null deja el concepto afuera (presentismo u horas).
     */
    private static List<ReciboService.Renglon> recibo(String basico, String presentismo, String horas,
            String porcentajeLey) {
        List<ReciboService.Renglon> r = new ArrayList<>();
        r.add(renglon("Sueldo básico", GrupoConcepto.REMUNERATIVO, basico, BASICO));
        if (presentismo != null) {
            r.add(renglon("Presentismo", GrupoConcepto.REMUNERATIVO, presentismo, PRESENTISMO));
        }
        if (horas != null) {
            r.add(renglon("Horas extra 50%", GrupoConcepto.REMUNERATIVO, horas, AL_50));
        }
        r.add(renglon("Viático", GrupoConcepto.NO_REMUNERATIVO, "300000.00", VIATICO));
        BigDecimal remunerativo = r.stream().filter(c -> c.grupo() == GrupoConcepto.REMUNERATIVO)
                .map(ReciboService.Renglon::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        r.add(renglon("Jubilación 11%", GrupoConcepto.DESCUENTO, porcentaje(remunerativo, "11"), JUBILACION));
        r.add(renglon("Ley 19.032 " + porcentajeLey + "%", GrupoConcepto.DESCUENTO, porcentaje(remunerativo,
                porcentajeLey), LEY));
        return r;
    }

    private static String porcentaje(BigDecimal base, String porcentaje) {
        return base.multiply(new BigDecimal(porcentaje)).divide(BigDecimal.valueOf(100), 2,
                java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Un recibo que coincide con el cálculo: 645.000 de bruto, descuentos del 11% y el 3%. */
    private static List<ReciboService.Renglon> reciboQueCoincide() {
        return recibo("500000.00", "100000.00", "45000.00", "3");
    }

    private ControlRecibo.Resultado revisar(List<ReciboService.Renglon> conceptos, BigDecimal cobro,
            List<ReciboService.Renglon> anterior) {
        return control.revisar(new ControlRecibo.Datos(TipoRecibo.SUELDO_MENSUAL, new BigDecimal("854700"), null,
                conceptos, cobro, calculo, anterior));
    }

    private static List<ControlRecibo.Tipo> tipos(ControlRecibo.Resultado r) {
        return r.hallazgos().stream().map(ControlRecibo.Hallazgo::tipo).toList();
    }

    @Test
    void elCalculoDeLaPruebaDaLoEsperado() {
        assertThat(calculo.getBruto()).isEqualByComparingTo("645000");
        assertThat(calculo.getNetoTotal()).isEqualByComparingTo("854700");
    }

    @Test
    void sinNadaRaroDiceQueCoincide() {
        ControlRecibo.Resultado r = revisar(reciboQueCoincide(), new BigDecimal("854700"), reciboQueCoincide());

        assertThat(r.sinNadaRaro()).isTrue();
        assertThat(r.conCalculo()).isTrue();
        assertThat(r.conCobro()).isTrue();
    }

    @Test
    void elNetoDelReciboDistintoDeLoCobradoSeMarca() {
        ControlRecibo.Resultado r = revisar(reciboQueCoincide(), new BigDecimal("850000"), null);

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.NETO_DISTINTO);
        assertThat(r.hallazgos().get(0).texto())
                .isEqualTo("El neto del recibo es $854.700 y vos cobraste $850.000 (diferencia $4.700).");
    }

    @Test
    void unPesoDeDiferenciaEnElNetoNoSeMarca() {
        assertThat(revisar(reciboQueCoincide(), new BigDecimal("854701"), null).sinNadaRaro()).isTrue();
    }

    @Test
    void sinCobroNoComparaElNeto() {
        ControlRecibo.Resultado r = revisar(reciboQueCoincide(), null, null);

        assertThat(r.sinNadaRaro()).isTrue();
        assertThat(r.conCobro()).isFalse();
    }

    @Test
    void lasHorasExtraPagadasDistintasDeLasCargadasSeMarcan() {
        List<ReciboService.Renglon> recibo = recibo("500000.00", "100000.00", "30000.00", "3");
        ControlRecibo.Resultado r = revisar(recibo, null, null);

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.CONCEPTO_DISTINTO);
        assertThat(r.hallazgos().get(0).texto())
                .isEqualTo("Horas al 50%: el recibo paga $30.000 y tu cálculo (10 h) da $45.000.");
    }

    @Test
    void elBasicoDistintoDeLaEscalaSeMarca() {
        List<ReciboService.Renglon> recibo = recibo("480000.00", "100000.00", "45000.00", "3");

        ControlRecibo.Resultado r = revisar(recibo, null, null);

        assertThat(r.hallazgos()).extracting(ControlRecibo.Hallazgo::texto)
                .contains("Sueldo básico: el recibo dice $480.000 y tu cálculo da $500.000.");
    }

    @Test
    void unPorcentajeDeDescuentoDistintoDeLaEscalaSeMarca() {
        // La ley 19.032 al 2,5% de 645.000 en lugar del 3%.
        List<ReciboService.Renglon> recibo = recibo("500000.00", "100000.00", "45000.00", "2.5");

        ControlRecibo.Resultado r = revisar(recibo, null, null);

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.PORCENTAJE_DISTINTO);
        assertThat(r.hallazgos().get(0).texto())
                .isEqualTo("Ley 19.032: te descontaron 2,5% y tu escala dice 3%.");
    }

    @Test
    void unDescuentoQueNoApareceEnElReciboSeMarca() {
        List<ReciboService.Renglon> recibo = reciboQueCoincide();
        recibo.remove(5);

        ControlRecibo.Resultado r = revisar(recibo, null, null);

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.CONCEPTO_FALTA);
        assertThat(r.hallazgos().get(0).texto()).contains("Ley 19.032", "no te lo descontaron", "$19.350");
    }

    @Test
    void unConceptoQueFaltaEnElReciboSeDiceUnaSolaVezAunqueEstabaElMesAnterior() {
        List<ReciboService.Renglon> recibo = recibo("500000.00", null, "45000.00", "3");

        // El presentismo estaba el mes anterior, pero ya se marcó contra el cálculo.
        ControlRecibo.Resultado r = revisar(recibo, null, reciboQueCoincide());

        assertThat(r.hallazgos()).extracting(ControlRecibo.Hallazgo::texto)
                .filteredOn(t -> t.contains("Presentismo")).hasSize(1);
        assertThat(r.hallazgos().get(0).texto()).isEqualTo("Presentismo: el recibo no lo trae y tu cálculo da $100.000.");
    }

    @Test
    void unConceptoQueEstabaElMesAnteriorYAhoraNoSeMarca() {
        List<ReciboService.Renglon> anterior = reciboQueCoincide();
        anterior.add(renglon("Premio por asistencia", GrupoConcepto.REMUNERATIVO, "20000.00", null));

        ControlRecibo.Resultado r = revisar(reciboQueCoincide(), null, anterior);

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.CONCEPTO_FALTA);
        assertThat(r.hallazgos().get(0).texto())
                .isEqualTo("Premio por asistencia estaba en el recibo del mes anterior y ahora no está.");
    }

    @Test
    void unConceptoNuevoSeMarca() {
        List<ReciboService.Renglon> recibo = reciboQueCoincide();
        recibo.add(5, renglon("Bono extraordinario", GrupoConcepto.NO_REMUNERATIVO, "50000.00", null));

        ControlRecibo.Resultado r = revisar(recibo, null, reciboQueCoincide());

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.CONCEPTO_NUEVO);
        assertThat(r.hallazgos().get(0).texto())
                .isEqualTo("Bono extraordinario es un concepto nuevo: no estaba en el recibo del mes anterior.");
    }

    @Test
    void elMismoConceptoConOtroNombreNoCuentaComoNuevo() {
        List<ReciboService.Renglon> anterior = reciboQueCoincide();
        anterior.set(2, renglon("Horas extras al 50 por ciento", GrupoConcepto.REMUNERATIVO, "45000.00", AL_50));

        assertThat(revisar(reciboQueCoincide(), null, anterior).sinNadaRaro()).isTrue();
    }

    @Test
    void unConceptoDeHorasQueEstabaElMesAnteriorNoFaltaSiEsteMesSeCargaronCeroHoras() {
        ControlRecibo.Resultado r = control.revisar(new ControlRecibo.Datos(TipoRecibo.SUELDO_MENSUAL,
                new BigDecimal("809700"), null, recibo("500000.00", "100000.00", null, "3"), null,
                calculo(BigDecimal.ZERO), reciboQueCoincide()));

        assertThat(r.hallazgos()).extracting(ControlRecibo.Hallazgo::texto)
                .noneMatch(t -> t.contains("estaba en el recibo del mes anterior"));
    }

    @Test
    void unConceptoDeHorasQueAparecePorqueSeCargaronHorasNoEsNuevo() {
        List<ReciboService.Renglon> anterior = recibo("500000.00", "100000.00", null, "3");

        ControlRecibo.Resultado r = revisar(reciboQueCoincide(), null, anterior);

        assertThat(r.sinNadaRaro()).isTrue();
    }

    @Test
    void siLosTotalesDelReciboNoCierranSeMarca() {
        ControlRecibo.Resultado r = control.revisar(new ControlRecibo.Datos(TipoRecibo.SUELDO_MENSUAL,
                new BigDecimal("854700"), "No cierra: los conceptos dan $854.000 y el neto impreso es $854.700.",
                reciboQueCoincide(), null, calculo, null));

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.NO_CIERRA);
        assertThat(r.hallazgos().get(0).texto()).startsWith("No cierra:");
    }

    @Test
    void sinCalculoSoloSeComparaConElMesAnteriorYSeDiceQueNoHayCalculo() {
        List<ReciboService.Renglon> recibo = reciboQueCoincide();
        recibo.set(2, renglon("Horas extra 50%", GrupoConcepto.REMUNERATIVO, "1.00", AL_50));

        ControlRecibo.Resultado r = control.revisar(new ControlRecibo.Datos(TipoRecibo.SUELDO_MENSUAL,
                new BigDecimal("854700"), null, recibo, null, null, null));

        assertThat(r.sinNadaRaro()).isTrue();
        assertThat(r.conCalculo()).isFalse();
    }

    @Test
    void unAguinaldoNoSeComparaConElCalculoDelSueldoNiConElMesAnterior() {
        ControlRecibo.Resultado r = control.revisar(new ControlRecibo.Datos(TipoRecibo.AGUINALDO,
                new BigDecimal("500000"), null, List.of(renglon("Sueldo anual complementario",
                        GrupoConcepto.REMUNERATIVO, "500000.00", null)), new BigDecimal("500000"), calculo,
                reciboQueCoincide()));

        assertThat(r.sinNadaRaro()).isTrue();
        assertThat(r.conCalculo()).isFalse();
    }

    @Test
    void variasCosasRarasSalenJuntas() {
        List<ReciboService.Renglon> recibo = recibo("500000.00", null, "45000.00", "2.5");

        ControlRecibo.Resultado r = revisar(recibo, new BigDecimal("800000"), null);

        assertThat(tipos(r)).containsExactly(ControlRecibo.Tipo.NETO_DISTINTO, ControlRecibo.Tipo.CONCEPTO_DISTINTO,
                ControlRecibo.Tipo.PORCENTAJE_DISTINTO);
    }
}
