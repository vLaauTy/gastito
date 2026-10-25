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
import java.util.Set;

import org.junit.jupiter.api.Test;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;

class RedactorResumenTest {

    static final Set<BigDecimal> PERMITIDOS = Set.of(new BigDecimal("52300.00"), new BigDecimal("12"),
            new BigDecimal("11"), new BigDecimal("14800.00"), new BigDecimal("3"), new BigDecimal("8900.00"));

    private final ClienteIa ia = mock(ClienteIa.class);
    private final RedactorResumen redactor = new RedactorResumen(ia, new Prompts(), new GastitoProperties(null, null,
            null, null, null, new GastitoProperties.Resumenes(LocalTime.of(21, 0), LocalTime.of(9, 0),
                    "claude-haiku-5-5", "claude-sonnet-5-5", 1024, new BigDecimal("3000"), List.of("Kiosco")), null));

    private void laIaEscribe(List<String> observaciones, String consejo) {
        when(ia.extraer(anyString(), anyLong(), anyString(), anyString(), eq(TextoResumenIa.class), any()))
                .thenReturn(new ClienteIa.Respuesta<>(new TextoResumenIa(observaciones, consejo), 9L));
    }

    @Test
    void unTextoConNumerosDeLosDatosPasa() {
        laIaEscribe(List.of("Gastaste un 12% más que la semana pasada.", "Hiciste 11 compras chicas por $14.800."),
                "Si bajás el kiosco a 3 veces por semana, ahorrás unos $8.900 por mes.");

        RedactorResumen.Redaccion r = redactor.redactar(TipoResumen.SEMANAL, "{}", PERMITIDOS);

        assertThat(r.observaciones()).hasSize(2);
        assertThat(r.consejo()).startsWith("Si bajás");
        assertThat(r.descartado()).isFalse();
        assertThat(r.usoIaId()).isEqualTo(9L);
    }

    @Test
    void unNumeroInventadoDescartaTodoElTexto() {
        laIaEscribe(List.of("Gastaste un 12% más que la semana pasada."),
                "Si dejás el kiosco, ahorrás $20.000 por mes.");

        RedactorResumen.Redaccion r = redactor.redactar(TipoResumen.SEMANAL, "{}", PERMITIDOS);

        assertThat(r.descartado()).isTrue();
        assertThat(r.tieneTexto()).isFalse();
        assertThat(r.usoIaId()).isEqualTo(9L); // la llamada se hizo y se cobró igual
    }

    @Test
    void sinIaSaleSoloElResumenNumerico() {
        when(ia.extraer(anyString(), anyLong(), anyString(), anyString(), eq(TextoResumenIa.class), any()))
                .thenThrow(new IaNoDisponibleException("sin red", null));

        RedactorResumen.Redaccion r = redactor.redactar(TipoResumen.MENSUAL, "{}", PERMITIDOS);

        assertThat(r.tieneTexto()).isFalse();
        assertThat(r.descartado()).isFalse();
        assertThat(r.usoIaId()).isNull();
    }

    @Test
    void hastaCuatroObservacionesSinVacias() {
        laIaEscribe(List.of("Uno.", " ", "Dos.", "Tres.", "Cuatro.", "Cinco."), "  ");

        RedactorResumen.Redaccion r = redactor.redactar(TipoResumen.SEMANAL, "{}", PERMITIDOS);

        assertThat(r.observaciones()).containsExactly("Uno.", "Dos.", "Tres.", "Cuatro.");
        assertThat(r.consejo()).isNull();
    }

    @Test
    void usaElModeloDeCadaTipoConPropositoResumen() {
        laIaEscribe(List.of("Uno."), null);

        redactor.redactar(TipoResumen.MENSUAL, "{\"x\":1}", PERMITIDOS);

        verify(ia).extraer(eq("claude-sonnet-5-5"), eq(1024L), anyString(), eq("{\"x\":1}"),
                eq(TextoResumenIa.class), eq(PropositoIa.RESUMEN));
    }

    @Test
    void losPromptsNoTienenMarcadoresSinReemplazar() {
        assertThat(new Prompts().cargar("resumen-semanal", Map.of())).doesNotContain("{{").contains("no hagas cuentas");
        assertThat(new Prompts().cargar("resumen-mensual", Map.of())).doesNotContain("{{").contains("ingresos");
    }
}
