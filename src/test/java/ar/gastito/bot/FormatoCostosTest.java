package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.ia.CostosIaService;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.TopeIaService;

class FormatoCostosTest {

    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    private final CostosIaService costos = mock(CostosIaService.class);
    private final FormatoMensajes mensajes = new FormatoMensajes(RELOJ);
    private final FormatoCostos formato = new FormatoCostos(costos, mensajes, RELOJ);

    private static CostosIaService.Total total(YearMonth mes, long llamadas, String costo) {
        return new CostosIaService.Total(mes, llamadas, llamadas * 1000, llamadas * 100, new BigDecimal(costo));
    }

    private static CostosIaService.PorProposito proposito(PropositoIa p, long llamadas, String costo) {
        return new CostosIaService.PorProposito(p, llamadas, llamadas * 1000, llamadas * 100, new BigDecimal(costo),
                llamadas == 0 ? BigDecimal.ZERO : new BigDecimal(costo).divide(BigDecimal.valueOf(llamadas), 6,
                        java.math.RoundingMode.HALF_UP));
    }

    private CostosIaService.Panel panel(CostosIaService.Total actual, CostosIaService.Total anterior,
            String proyeccion, BigDecimal tope, Integer porcentaje, CostosIaService.PorProposito... propositos) {
        return new CostosIaService.Panel(OCTUBRE, true, actual, anterior,
                new CostosIaService.Proyeccion(new BigDecimal(proyeccion), 13, 31),
                new CostosIaService.Tope(tope, porcentaje), List.of(propositos),
                List.of(new CostosIaService.Dia(1, LocalDate.of(2026, 10, 1), BigDecimal.ZERO, false)), List.of(),
                List.of());
    }

    @Test
    void usdMuestraDosDecimalesYMasSiEsMenosDeUnCentavo() {
        assertThat(FormatoCostos.usd(new BigDecimal("1.62"))).isEqualTo("USD 1,62");
        assertThat(FormatoCostos.usd(new BigDecimal("0.1"))).isEqualTo("USD 0,10");
        assertThat(FormatoCostos.usd(new BigDecimal("1234.5"))).isEqualTo("USD 1.234,50");
        assertThat(FormatoCostos.usd(new BigDecimal("0.000242"))).isEqualTo("USD 0,000242");
        assertThat(FormatoCostos.usd(new BigDecimal("0.0006"))).isEqualTo("USD 0,0006");
        assertThat(FormatoCostos.usd(BigDecimal.ZERO)).isEqualTo("USD 0,00");
    }

    @Test
    void elTopeRedondoNoLlevaDecimales() {
        assertThat(FormatoCostos.usdTope(new BigDecimal("2"))).isEqualTo("USD 2");
        assertThat(FormatoCostos.usdTope(new BigDecimal("2.00"))).isEqualTo("USD 2");
        assertThat(FormatoCostos.usdTope(new BigDecimal("2.5"))).isEqualTo("USD 2,50");
    }

    @Test
    void comandoMuestraTotalProyeccionYDesglose() {
        when(costos.panel(OCTUBRE)).thenReturn(panel(total(OCTUBRE, 134, "1.62"),
                total(YearMonth.of(2026, 9), 90, "1.05"), "3.86", new BigDecimal("2"), 81,
                proposito(PropositoIa.TEXTO, 80, "0.35"), proposito(PropositoIa.AUDIO, 0, "0"),
                proposito(PropositoIa.TICKET, 40, "1.07"), proposito(PropositoIa.CONSULTA, 1, "0.20")));

        assertThat(formato.comando()).isEqualTo("""
                💸 <b>IA en octubre 2026</b>
                Llevás <b>USD 1,62</b> en 134 llamadas (81% del tope de USD 2).
                Proyección a fin de mes: <b>USD 3,86</b>
                El mes pasado: USD 1,05

                • Texto: 80 llamadas · USD 0,35
                • Ticket: 40 llamadas · USD 1,07
                • Consulta: 1 llamada · USD 0,20""");
    }

    @Test
    void comandoSinTopeNiMesAnterior() {
        when(costos.panel(OCTUBRE)).thenReturn(panel(total(OCTUBRE, 3, "0.0021"),
                total(YearMonth.of(2026, 9), 0, "0"), "0.0050", null, null,
                proposito(PropositoIa.TEXTO, 3, "0.0021")));

        assertThat(formato.comando()).isEqualTo("""
                💸 <b>IA en octubre 2026</b>
                Llevás <b>USD 0,0021</b> en 3 llamadas.
                Proyección a fin de mes: <b>USD 0,0050</b>

                • Texto: 3 llamadas · USD 0,0021""");
    }

    @Test
    void comandoSinUsoDelMes() {
        when(costos.panel(OCTUBRE)).thenReturn(panel(total(OCTUBRE, 0, "0"), total(YearMonth.of(2026, 9), 5, "0.1"),
                "0", new BigDecimal("2"), 0));

        assertThat(formato.comando()).isEqualTo("En octubre 2026 todavía no se usó la IA.");
    }

    @Test
    void avisoDel80() {
        var aviso = new TopeIaService.Aviso(OCTUBRE, 80, new BigDecimal("1.62"), new BigDecimal("2"), 81);

        Accion.Enviar mensaje = formato.aviso(aviso);

        assertThat(mensaje.html()).isEqualTo("⚠️ <b>La IA lleva USD 1,62 este mes</b> (81% del tope de USD 2).");
        assertThat(mensaje.teclado().isVacio()).isTrue();
    }

    @Test
    void avisoDel100() {
        var aviso = new TopeIaService.Aviso(OCTUBRE, 100, new BigDecimal("2.05"), new BigDecimal("2"), 102);

        assertThat(formato.aviso(aviso).html()).isEqualTo("⚠️ <b>La IA llegó al tope del mes.</b>\n"
                + "Lleva USD 2,05 este mes (102% del tope de USD 2). No bloqueo nada, solo te aviso.");
    }

    @Test
    void losNombresDeLosPropositos() {
        for (PropositoIa p : PropositoIa.values()) {
            assertThat(FormatoCostos.nombre(p)).isNotBlank();
        }
        assertThat(FormatoCostos.nombre(PropositoIa.RECIBO)).isEqualTo("Recibo");
        assertThat(LocalDateTime.now(RELOJ).getYear()).isEqualTo(2026);
    }
}
