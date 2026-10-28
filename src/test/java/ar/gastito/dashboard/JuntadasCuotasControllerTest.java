package ar.gastito.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.juntada.EstadoParticipante;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;

@WebMvcTest(JuntadasCuotasController.class)
@ActiveProfiles("test")
@EnableConfigurationProperties(DashboardProperties.class)
class JuntadasCuotasControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JuntadaService juntadas;

    @MockitoBean
    PlanCuotasService cuotas;

    private static VistaJuntada juntada(long id, String restanteDeAna) {
        BigDecimal resto = new BigDecimal(restanteDeAna);
        EstadoParticipante estado = resto.signum() > 0 ? EstadoParticipante.PENDIENTE : EstadoParticipante.PAGADO;
        return new VistaJuntada(id, id, "Asado", "Salidas", LocalDateTime.of(2026, 10, 3, 21, 0),
                new BigDecimal("30000"), new BigDecimal("10000"), resto, 3, "Mercado Pago", true,
                List.of(new VistaJuntada.Persona(1L, "Ana", new BigDecimal("10000"), new BigDecimal("10000").subtract(resto), resto, estado, false)));
    }

    @Test
    void separaLasAbiertasDeLasCerradasYSumaLoQueMeDeben() throws Exception {
        when(juntadas.todas()).thenReturn(List.of(juntada(1, "10000"), juntada(2, "0"), juntada(3, "4000")));

        mvc.perform(get("/api/juntadas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aCobrar").value(14000.0))
                .andExpect(jsonPath("$.personasQueDeben").value(2))
                .andExpect(jsonPath("$.abiertas.length()").value(2))
                .andExpect(jsonPath("$.cerradas.length()").value(1))
                .andExpect(jsonPath("$.cerradas[0].juntadaId").value(2));
    }

    @Test
    void sinJuntadasTodoEnCero() throws Exception {
        when(juntadas.todas()).thenReturn(List.of());

        mvc.perform(get("/api/juntadas"))
                .andExpect(jsonPath("$.aCobrar").value(0))
                .andExpect(jsonPath("$.abiertas").isEmpty());
    }

    @Test
    void lasCuotasTraenLoComprometidoMesAMes() throws Exception {
        when(cuotas.activos()).thenReturn(List.of());
        when(cuotas.comprometido(6)).thenReturn(List.of(
                new PlanCuotasService.MesComprometido(YearMonth.of(2026, 10), new BigDecimal("5000"), 1, 71),
                new PlanCuotasService.MesComprometido(YearMonth.of(2026, 11), new BigDecimal("7000"), 2, 100)));

        mvc.perform(get("/api/cuotas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalComprometido").value(12000.0))
                .andExpect(jsonPath("$.comprometido[1].mes").value("2026-11"))
                .andExpect(jsonPath("$.comprometido[1].cuotas").value(2));
    }
}
