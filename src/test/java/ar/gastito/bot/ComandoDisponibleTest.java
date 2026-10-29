package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import ar.gastito.consulta.HerramientasConsulta;

class ComandoDisponibleTest {

    private final HerramientasConsulta herramientas = mock(HerramientasConsulta.class);
    private final ComandoDisponible comando = new ComandoDisponible(herramientas, new FormatoMensajes(RELOJ), RELOJ);

    @Test
    void ingresosMenosLoGastadoDelMes() {
        when(herramientas.ingresosYDisponible(YearMonth.of(2026, 10))).thenReturn(
                new HerramientasConsulta.IngresosYDisponible("2026-10", new BigDecimal("980000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("980000"), new BigDecimal("420000"),
                        new BigDecimal("560000"), 43));

        assertThat(comando.responder()).isEqualTo("""
                💰 <b>Octubre 2026</b>
                Te queda <b>$560.000</b>
                Ingresos: $980.000 · Gastado: $420.000 (43%)""");
    }

    @Test
    void sinIngresosNoDiceQueQuedaPlataNegativa() {
        when(herramientas.ingresosYDisponible(YearMonth.of(2026, 10))).thenReturn(
                new HerramientasConsulta.IngresosYDisponible("2026-10", BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("420000"), new BigDecimal("-420000"), null));

        assertThat(comando.responder()).contains("Todavía no cargaste ingresos").contains("Gastado: <b>$420.000</b>")
                .doesNotContain("-");
    }
}
