package ar.gastito.prueba;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.LectorPdf;
import ar.gastito.recibo.ReciboLeido;
import ar.gastito.recibo.TipoRecibo;
import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.TipoConceptoSueldo;

class PlanRecibosPruebaTest {

    static final ConceptoSueldo BASICO = concepto(1, "Sueldo básico", TipoConceptoSueldo.REMUNERATIVO_FIJO);
    static final ConceptoSueldo HORAS = ConceptoSueldo.conId(2L,
            new ConceptoSueldo("Horas al 50%", TipoConceptoSueldo.HORAS, 3, 2, 2));
    static final ConceptoSueldo VIATICO = concepto(3, "Viático", TipoConceptoSueldo.NO_REMUNERATIVO);
    static final ConceptoSueldo JUBILACION = concepto(4, "Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE);
    static final ConceptoSueldo LEY = concepto(5, "Ley 19.032", TipoConceptoSueldo.DESCUENTO_PORCENTAJE);

    private static ConceptoSueldo concepto(long id, String nombre, TipoConceptoSueldo tipo) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, tipo, null, null, (int) id));
    }

    /** Básico 500.000 + 10 horas al 50% a 3.000 (45.000): bruto 545.000 y viático 300.000. */
    private final CalculoSueldo calculo = new CalculoSueldo(YearMonth.of(2026, 10), 1L,
            CalculadoraSueldo.calcular(List.of(BASICO, HORAS, VIATICO, JUBILACION, LEY),
                    new EscalaSueldo(LocalDate.of(2026, 1, 1), new BigDecimal("3000"), Map.of(
                            BASICO.getId(), new BigDecimal("500000"), VIATICO.getId(), new BigDecimal("300000"),
                            JUBILACION.getId(), new BigDecimal("11"), LEY.getId(), new BigDecimal("3"))),
                    Map.of(HORAS.getId(), new BigDecimal("10")), Map.of()),
            LocalDateTime.of(2026, 10, 31, 10, 0));

    private static BigDecimal descuento(ReciboLeido r, String nombre) {
        return r.conceptos().stream().filter(c -> c.descripcion().equals(nombre)).findFirst().orElseThrow().importe();
    }

    @Test
    void elReciboDelSueldoSaleDelCalculoYCierraLaCuenta() {
        ReciboLeido r = PlanRecibosPrueba.sueldo(calculo, false);

        assertThat(r.tipo()).isEqualTo(TipoRecibo.SUELDO_MENSUAL);
        assertThat(r.periodo()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(r.fechaPago()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(r.conceptos()).extracting(ReciboLeido.Concepto::descripcion).containsExactly("Sueldo básico",
                "Horas al 50%", "Viático", "Jubilación", "Ley 19.032");
        assertThat(r.bruto()).isEqualByComparingTo("845000");
        assertThat(r.descuentos()).isEqualByComparingTo(calculo.getDescuentos());
        // Un recibo que coincide con el cálculo cobra lo mismo.
        assertThat(r.neto()).isEqualByComparingTo(calculo.getNetoTotal());
        assertThat(r.aviso()).isNull();
    }

    @Test
    void elReciboRaroDescuentaLaLeyAl2Coma5PorCiento() {
        ReciboLeido normal = PlanRecibosPrueba.sueldo(calculo, false);
        ReciboLeido raro = PlanRecibosPrueba.sueldo(calculo, true);

        // 2,5% de 545.000 en lugar del 3%.
        assertThat(descuento(raro, "Ley 19.032")).isEqualByComparingTo("13625.00");
        assertThat(descuento(raro, "Ley 19.032")).isLessThan(descuento(normal, "Ley 19.032"));
        assertThat(descuento(raro, "Jubilación")).isEqualByComparingTo(descuento(normal, "Jubilación"));
        assertThat(raro.neto()).isGreaterThan(normal.neto());
    }

    @Test
    void elAguinaldoEsLaMitadDeLaMejorRemuneracion() {
        ReciboLeido r = PlanRecibosPrueba.aguinaldo(YearMonth.of(2026, 6), new BigDecimal("1000000"));

        assertThat(r.tipo()).isEqualTo(TipoRecibo.AGUINALDO);
        assertThat(r.fechaPago()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(r.bruto()).isEqualByComparingTo("500000");
        assertThat(r.descuentos()).isEqualByComparingTo("85000");
        assertThat(r.neto()).isEqualByComparingTo("415000");
        assertThat(r.conceptos().stream().filter(c -> c.grupo() == GrupoConcepto.DESCUENTO)).hasSize(3);
    }

    @Test
    void elPdfDeUnReciboDePruebaTieneTextoYElNeto() {
        ReciboLeido r = PlanRecibosPrueba.sueldo(calculo, false);

        String texto = new LectorPdf().texto(PlanRecibosPrueba.pdf(r)).orElseThrow();

        assertThat(texto).contains("DATOS DE PRUEBA").contains("Sueldo basico").contains("NETO A COBRAR: "
                + r.neto().toPlainString());
    }
}
