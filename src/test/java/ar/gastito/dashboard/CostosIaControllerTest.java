package ar.gastito.dashboard;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.ia.CostosIaService;
import ar.gastito.ia.PropositoIa;

@WebMvcTest(CostosIaController.class)
@ActiveProfiles("test")
@Import(CostosIaControllerTest.Config.class)
class CostosIaControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            ZoneId zona = ZoneId.of("America/Argentina/Buenos_Aires");
            return Clock.fixed(LocalDateTime.of(2026, 10, 15, 10, 0).atZone(zona).toInstant(), zona);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CostosIaService costos;

    private static CostosIaService.Panel panel(YearMonth mes) {
        return new CostosIaService.Panel(mes, true,
                new CostosIaService.Total(mes, 6, 14600, 3120, new BigDecimal("0.004900")),
                new CostosIaService.Total(mes.minusMonths(1), 2, 2, 2, new BigDecimal("0.003000")),
                new CostosIaService.Proyeccion(new BigDecimal("0.010127"), 15, 31),
                new CostosIaService.Tope(new BigDecimal("2"), 0),
                List.of(new CostosIaService.PorProposito(PropositoIa.TICKET, 2, 5700, 1600,
                        new BigDecimal("0.001400"), new BigDecimal("0.000700"))),
                List.of(new CostosIaService.Dia(1, LocalDate.of(2026, 10, 1), new BigDecimal("0.000500"), false),
                        new CostosIaService.Dia(16, LocalDate.of(2026, 10, 16), new BigDecimal("0.000000"), true)),
                List.of(new CostosIaService.MesHistorial(mes, 6, new BigDecimal("0.004900"), new BigDecimal("100.0"))),
                List.of(new CostosIaService.LlamadaCara(7L, LocalDateTime.of(2026, 10, 15, 9, 0),
                        PropositoIa.CONSULTA, 4000, 400, new BigDecimal("0.002000"))));
    }

    @Test
    void elPanelDelMesActualPorOmision() throws Exception {
        when(costos.panel(YearMonth.of(2026, 10))).thenReturn(panel(YearMonth.of(2026, 10)));

        mvc.perform(get("/api/costos-ia/panel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-10"))
                .andExpect(jsonPath("$.mesActual").value(true))
                .andExpect(jsonPath("$.actual.llamadas").value(6))
                .andExpect(jsonPath("$.actual.costoUsd").value(0.004900))
                .andExpect(jsonPath("$.anterior.mes").value("2026-09"))
                .andExpect(jsonPath("$.proyeccion.costoUsd").value(0.010127))
                .andExpect(jsonPath("$.proyeccion.diasTranscurridos").value(15))
                .andExpect(jsonPath("$.tope.topeUsd").value(2))
                .andExpect(jsonPath("$.tope.porcentaje").value(0))
                .andExpect(jsonPath("$.porProposito[0].proposito").value("TICKET"))
                .andExpect(jsonPath("$.porProposito[0].costoPromedioUsd").value(0.000700))
                .andExpect(jsonPath("$.porDia[1].futuro").value(true))
                .andExpect(jsonPath("$.historial[0].porcentajeDelMaximo").value(100.0))
                .andExpect(jsonPath("$.masCaras[0].proposito").value("CONSULTA"))
                .andExpect(jsonPath("$.masCaras[0].tokensEntrada").value(4000));
    }

    @Test
    void elPanelDeOtroMes() throws Exception {
        when(costos.panel(YearMonth.of(2026, 7))).thenReturn(panel(YearMonth.of(2026, 7)));

        mvc.perform(get("/api/costos-ia/panel?mes=2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-07"));
        verify(costos).panel(YearMonth.of(2026, 7));
    }

    @Test
    void unMesInvalidoEsUnPedidoMalHecho() throws Exception {
        mvc.perform(get("/api/costos-ia/panel?mes=julio")).andExpect(status().isBadRequest());
    }
}
