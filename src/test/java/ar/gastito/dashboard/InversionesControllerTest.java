package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.inversion.InversionService;
import ar.gastito.inversion.PanelInversiones;
import ar.gastito.movimiento.Origen;

@WebMvcTest(InversionesController.class)
@ActiveProfiles("test")
@EnableConfigurationProperties(DashboardProperties.class)
class InversionesControllerTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 11, 5, 11, 0).atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InversionService inversiones;

    @MockitoBean
    PanelInversiones panel;

    @MockitoBean
    DashboardService dashboard;

    private static DashboardService.Resumen resumen(String disponible) {
        return new DashboardService.Resumen(YearMonth.of(2026, 11), true, 5, new BigDecimal("600000"), 10,
                new BigDecimal("120000"), null, null,
                new DashboardService.Disponible(new BigDecimal(disponible), 60));
    }

    @Test
    void sinInversionesNoHayLinea() throws Exception {
        when(panel.hayInversiones()).thenReturn(false);

        mvc.perform(get("/api/inversiones/resumen?mes=2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hay").value(false));
        verifyNoInteractions(dashboard);
    }

    @Test
    void elTotalConLoDisponibleNoCuentaDosVecesLoQueSePasoEsteMes() throws Exception {
        when(panel.hayInversiones()).thenReturn(true);
        when(panel.totalInvertido()).thenReturn(new BigDecimal("1070000.00"));
        when(panel.aportesNetos(any(), any())).thenReturn(new BigDecimal("200000.00"));
        when(dashboard.resumen(YearMonth.of(2026, 11))).thenReturn(resumen("400000.00"));

        // 400.000 disponibles − 200.000 que ya están adentro de las inversiones + 1.070.000 invertidos.
        mvc.perform(get("/api/inversiones/resumen?mes=2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hay").value(true))
                .andExpect(jsonPath("$.invertido").value(1070000.0))
                .andExpect(jsonPath("$.movidoEnElMes").value(200000.0))
                .andExpect(jsonPath("$.totalConDisponible").value(1270000.0));
    }

    @Test
    void unAporteDesdeElDashboardEsEntreCuentas() throws Exception {
        mvc.perform(post("/api/inversiones/operaciones").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tipo": "APORTE", "cuentaId": 10, "otraCuentaId": 1, "monto": 200000,
                                 "fecha": "2026-11-04"}"""))
                .andExpect(status().isOk());

        verify(inversiones).aportar(eq(10L), isNull(), eq(new BigDecimal("200000")), eq(1L),
                eq(LocalDate.of(2026, 11, 4).atTime(12, 0)), eq(Origen.DASHBOARD), isNull());
    }

    @Test
    void unaFechaFuturaNoSeAcepta() throws Exception {
        mvc.perform(post("/api/inversiones/valuaciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuentaId\": 10, \"monto\": 870000, \"fecha\": \"2026-12-01\"}"))
                .andExpect(status().isBadRequest());
    }
}
