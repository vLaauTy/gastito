package ar.gastito.resumen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;

class RedactorResumenTest {

    /** Una semana: total, cantidad, variación, días sin gastos y hormiga ya se muestran arriba. */
    static final String DATOS = """
            {"totalGastado": 52300.00, "cantidadGastos": 23,
             "comparacion": {"desde": "2026-10-12", "total": 46700.00, "variacionPorcentaje": 12},
             "porCategoria": [
               {"nombre": "Kiosco", "total": 14800.00, "cantidad": 7, "totalAnterior": 8000.00,
                "variacionPorcentaje": 85},
               {"nombre": "Supermercado", "total": 21400.00, "cantidad": 3, "totalAnterior": 30000.00,
                "variacionPorcentaje": -29}],
             "diasSinGastos": 3,
             "hormiga": {"umbral": 3000.00, "cantidad": 9, "total": 8199.00},
             "escenariosAhorro": [{"tipo": "HORMIGA", "valorObjetivo": 3, "ahorroPeriodo": 2050.00,
                                   "ahorroMensual": 8900.00}],
             "pocosDatos": false}""";

    private final ClienteIa ia = mock(ClienteIa.class);
    private final RedactorResumen redactor = new RedactorResumen(ia, new Prompts(), new GastitoProperties(null, null,
            null, null, null, new GastitoProperties.Resumenes(LocalTime.of(21, 0), LocalTime.of(9, 0),
                    "claude-haiku-5-5", "claude-sonnet-5-5", 1024, new BigDecimal("3000"), List.of("Kiosco"),
                    new GastitoProperties.Minimo(5, 3), new GastitoProperties.Minimo(15, 7)), null));

    private static RedactorResumen.Pedido pedido(TipoResumen tipo, boolean conConsejo) {
        try {
            return RedactorResumen.Pedido.de(tipo, (ObjectNode) new ObjectMapper().readTree(DATOS), conConsejo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RedactorResumen.Pedido semana() {
        return pedido(TipoResumen.SEMANAL, true);
    }

    private void laIaEscribe(List<String> observaciones, String consejo) {
        when(ia.extraer(anyString(), anyLong(), anyString(), anyString(), eq(TextoResumenIa.class), any()))
                .thenReturn(new ClienteIa.Respuesta<>(new TextoResumenIa(observaciones, consejo), 9L));
    }

    @Test
    void unTextoQueAportaAlgoNuevoPasa() {
        laIaEscribe(List.of("El kiosco subió un 85% y pasó de $8.000 a $14.800."),
                "Si bajás el kiosco a 3 veces por semana, ahorrás unos $8.900 por mes.");

        RedactorResumen.Redaccion r = redactor.redactar(semana());

        assertThat(r.observaciones()).hasSize(1);
        assertThat(r.consejo()).startsWith("Si bajás");
        assertThat(r.descartado()).isFalse();
        assertThat(r.usoIaId()).isEqualTo(9L);
    }

    @Test
    void lasObservacionesQueRepitenElResumenNumericoSeSacan() {
        laIaEscribe(List.of(
                "Gastaste un 12% más que la semana pasada.",
                "La semana pasada habías gastado $46.700.",
                "Hiciste 9 compras chicas por $8.199.",
                "En total fueron 23 gastos.",
                "El súper bajó un 29%."), null);

        RedactorResumen.Redaccion r = redactor.redactar(semana());

        assertThat(r.observaciones()).containsExactly("El súper bajó un 29%.");
        assertThat(r.descartado()).isFalse();
    }

    @Test
    void unNumeroQueTambienEsDeOtroDatoNoCuentaComoRepetido() {
        // 3 son los días sin gastos, pero también las compras del súper.
        laIaEscribe(List.of("El súper fueron solo 3 compras grandes, de $21.400 en total."), null);

        assertThat(redactor.redactar(semana()).observaciones()).hasSize(1);
    }

    @Test
    void elPedidoSeparaLosNumerosMostradosDelResto() {
        RedactorResumen.Pedido p = semana();

        assertThat(p.mostrados()).extracting(m -> m.stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("52300", "23", "46700", "12", "3", "9", "8199");
        assertThat(p.otros()).extracting(o -> o.stripTrailingZeros().toPlainString())
                .contains("14800", "7", "85", "3", "8900", "12").doesNotContain("52300", "23", "46700", "8199");
        // El 12 de la fecha "2026-10-12" no cuenta para un "12%".
        assertThat(p.otrosSinFechas()).extracting(o -> o.stripTrailingZeros().toPlainString())
                .doesNotContain("12", "2026").contains("85");
        assertThat(p.datosJson()).contains("\"totalGastado\""); // la IA ve todo, solo se controla distinto
    }

    @Test
    void sinNadaNuevoQuedanCeroObservaciones() {
        laIaEscribe(List.of(), "Si bajás el kiosco a 3 veces por semana, ahorrás unos $8.900 por mes.");

        RedactorResumen.Redaccion r = redactor.redactar(semana());

        assertThat(r.observaciones()).isEmpty();
        assertThat(r.tieneTexto()).isTrue();
    }

    @Test
    void conPocosDatosNoSeUsaElConsejo() {
        laIaEscribe(List.of("El kiosco subió un 85%."), "Si bajás el kiosco, ahorrás $8.900 por mes.");

        RedactorResumen.Redaccion r = redactor.redactar(pedido(TipoResumen.SEMANAL, false));

        assertThat(r.consejo()).isNull();
        assertThat(r.observaciones()).containsExactly("El kiosco subió un 85%.");
    }

    @Test
    void unNumeroInventadoDescartaTodoElTexto() {
        laIaEscribe(List.of("El kiosco subió un 85%."), "Si dejás el kiosco, ahorrás $20.000 por mes.");

        RedactorResumen.Redaccion r = redactor.redactar(semana());

        assertThat(r.descartado()).isTrue();
        assertThat(r.tieneTexto()).isFalse();
        assertThat(r.usoIaId()).isEqualTo(9L); // la llamada se hizo y se cobró igual
    }

    @Test
    void sinIaSaleSoloElResumenNumerico() {
        when(ia.extraer(anyString(), anyLong(), anyString(), anyString(), eq(TextoResumenIa.class), any()))
                .thenThrow(new IaNoDisponibleException("sin red", null));

        RedactorResumen.Redaccion r = redactor.redactar(pedido(TipoResumen.MENSUAL, true));

        assertThat(r.tieneTexto()).isFalse();
        assertThat(r.descartado()).isFalse();
        assertThat(r.usoIaId()).isNull();
    }

    @Test
    void hastaTresObservacionesSinVacias() {
        laIaEscribe(List.of("Uno.", " ", "Dos.", "Tres.", "Cuatro."), "  ");

        RedactorResumen.Redaccion r = redactor.redactar(semana());

        assertThat(r.observaciones()).containsExactly("Uno.", "Dos.", "Tres.");
        assertThat(r.consejo()).isNull();
    }

    @Test
    void usaElModeloDeCadaTipoConPropositoResumen() {
        laIaEscribe(List.of("Uno."), null);

        RedactorResumen.Pedido p = pedido(TipoResumen.MENSUAL, true);
        redactor.redactar(p);

        verify(ia).extraer(eq("claude-sonnet-5-5"), eq(1024L), anyString(), eq(p.datosJson()),
                eq(TextoResumenIa.class), eq(PropositoIa.RESUMEN));
    }

    @Test
    void losPromptsNoTienenMarcadoresSinReemplazar() {
        assertThat(new Prompts().cargar("resumen-semanal", Map.of())).doesNotContain("{{")
                .contains("no hagas cuentas").contains("No los repitas").contains("ahorroMensual es null");
        assertThat(new Prompts().cargar("resumen-mensual", Map.of())).doesNotContain("{{").contains("ingresos")
                .contains("No los repitas");
    }
}
