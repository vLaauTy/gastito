package ar.gastito.prueba;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.LectorPdf;
import ar.gastito.recibo.ReciboLeido;
import ar.gastito.recibo.TipoRecibo;

class PlanRecibosPruebaTest {

    private final List<ReciboLeido> recibos = PlanRecibosPrueba.armar(YearMonth.of(2026, 10));

    private List<ReciboLeido> sueldos() {
        return recibos.stream().filter(r -> r.tipo() == TipoRecibo.SUELDO_MENSUAL).toList();
    }

    @Test
    void sonSeisSueldosSeguidosYUnAguinaldo() {
        assertThat(sueldos()).extracting(ReciboLeido::periodo).containsExactly(YearMonth.of(2026, 5),
                YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9),
                YearMonth.of(2026, 10));
        assertThat(recibos).filteredOn(r -> r.tipo() == TipoRecibo.AGUINALDO).hasSize(1)
                .first().extracting(ReciboLeido::periodo).isEqualTo(YearMonth.of(2026, 6));
    }

    @Test
    void cadaReciboCierraLaCuentaDelNeto() {
        for (ReciboLeido r : recibos) {
            BigDecimal remuneraciones = r.conceptos().stream().filter(c -> c.grupo() != GrupoConcepto.DESCUENTO)
                    .map(ReciboLeido.Concepto::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal descuentos = r.conceptos().stream().filter(c -> c.grupo() == GrupoConcepto.DESCUENTO)
                    .map(ReciboLeido.Concepto::importe).reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(remuneraciones.subtract(descuentos)).as("%s %s", r.tipo(), r.periodo())
                    .isEqualByComparingTo(r.neto());
            assertThat(r.bruto()).isEqualByComparingTo(remuneraciones);
            assertThat(r.descuentos()).isEqualByComparingTo(descuentos);
            assertThat(r.aviso()).isNull();
        }
    }

    @Test
    void hayUnAumentoDeMediadosDeLaSerie() {
        List<ReciboLeido> sueldos = sueldos();

        assertThat(sueldos.get(3).neto()).isGreaterThan(sueldos.get(2).neto().multiply(new BigDecimal("1.10")));
        assertThat(sueldos.get(2).neto()).isEqualByComparingTo(sueldos.get(1).neto());
        // Ganancias aparece con el aumento.
        assertThat(sueldos.get(2).conceptos()).extracting(ReciboLeido.Concepto::descripcion)
                .doesNotContain("Impuesto a las ganancias");
        assertThat(sueldos.get(3).conceptos()).extracting(ReciboLeido.Concepto::descripcion)
                .contains("Impuesto a las ganancias");
    }

    @Test
    void cualquierVentanaDeSeisMesesTieneUnAguinaldo() {
        for (int mes = 1; mes <= 12; mes++) {
            assertThat(PlanRecibosPrueba.armar(YearMonth.of(2026, mes)))
                    .as("hasta el mes %d", mes).anyMatch(r -> r.tipo() == TipoRecibo.AGUINALDO);
        }
    }

    @Test
    void elPdfDeUnReciboDePruebaTieneTextoYElNeto() {
        ReciboLeido r = sueldos().get(5);

        String texto = new LectorPdf().texto(PlanRecibosPrueba.pdf(r)).orElseThrow();

        assertThat(texto).contains("DATOS DE PRUEBA").contains("Sueldo basico").contains("NETO A COBRAR: "
                + r.neto().toPlainString());
    }
}
