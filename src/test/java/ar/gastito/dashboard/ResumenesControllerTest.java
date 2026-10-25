package ar.gastito.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.resumen.DatosResumen;
import ar.gastito.resumen.Resumen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;

@WebMvcTest(ResumenesController.class)
@ActiveProfiles("test")
@Import(ResumenesControllerTest.Config.class)
class ResumenesControllerTest {

    /** El filtro de acceso también corre: los pedidos de MockMvc vienen de 127.0.0.1 a localhost y pasan. */
    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config {
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ResumenService resumenes;

    @Test
    void elUltimoResumenConSusObservaciones() throws Exception {
        LocalDate lunes = LocalDate.of(2026, 10, 19);
        Resumen r = new Resumen(TipoResumen.SEMANAL, lunes, LocalDate.of(2026, 10, 22), true, false, "{}",
                List.of("Hiciste 11 compras chicas.", "El súper bajó un 29%."), "Si bajás el kiosco, ahorrás $8.900.",
                false, null, LocalDateTime.of(2026, 10, 22, 10, 0));
        DatosResumen d = new DatosResumen(new DatosResumen.Periodo("SEMANAL", lunes, LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 25), true, 4), BigDecimal.TEN, 1, null, List.of(), null, null, 3, null, null,
                List.of(), null, null, List.of());
        when(resumenes.ultimo()).thenReturn(Optional.of(new ResumenService.Armado(r, d)));

        mvc.perform(get("/api/resumenes/ultimo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("SEMANAL"))
                .andExpect(jsonPath("$.desde").value("2026-10-19"))
                .andExpect(jsonPath("$.hasta").value("2026-10-22"))
                .andExpect(jsonPath("$.fin").value("2026-10-25"))
                .andExpect(jsonPath("$.parcial").value(true))
                .andExpect(jsonPath("$.observaciones.length()").value(2))
                .andExpect(jsonPath("$.consejo").value("Si bajás el kiosco, ahorrás $8.900."))
                .andExpect(jsonPath("$.textoIaDescartado").value(false));
    }

    @Test
    void sinResumenesEs204() throws Exception {
        when(resumenes.ultimo()).thenReturn(Optional.empty());

        mvc.perform(get("/api/resumenes/ultimo")).andExpect(status().isNoContent());
    }
}
