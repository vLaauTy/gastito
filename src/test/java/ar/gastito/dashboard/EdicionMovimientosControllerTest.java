package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.dashboard.EdicionMovimientos.Alcance;
import ar.gastito.movimiento.MovimientoEspecialException;

@WebMvcTest(EdicionMovimientosController.class)
@ActiveProfiles("test")
@Import(EdicionMovimientosControllerTest.Config.class)
class EdicionMovimientosControllerTest {

    /** El filtro de acceso también corre: los pedidos de MockMvc vienen de 127.0.0.1 a localhost y pasan. */
    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config {
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EdicionMovimientos edicion;

    private static final String CAMBIO = """
            {"monto": "3.500", "fecha": "2026-10-20", "descripcion": "Pancho", "categoriaId": 1,
             "medioPagoId": null, "cuentaId": 2}""";

    @Test
    void putEditaYDevuelveLaFilaComoQueda() throws Exception {
        when(edicion.editar(7L, new BigDecimal("3500"), LocalDate.of(2026, 10, 20), "Pancho", 1L, null, 2L))
                .thenReturn(new ConsultaMovimientos.Fila(7L, LocalDateTime.of(2026, 10, 20, 16, 42), "Pancho", 1L,
                        "Kiosco", "🍬", "Galicia", false, "TEXTO", new BigDecimal("3500.00"), false, false, false,
                        null, 2L, null, null, null));

        mvc.perform(put("/api/movimientos/7").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monto").value(3500.00))
                .andExpect(jsonPath("$.cuentaId").value(2))
                .andExpect(jsonPath("$.fechaHora").value("2026-10-20T16:42:00"));
    }

    @Test
    void unMontoQueNoSeEntiendeEsUn400() throws Exception {
        mvc.perform(put("/api/movimientos/7").contentType(MediaType.APPLICATION_JSON)
                .content(CAMBIO.replace("3.500", "mucho")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No entendí el monto. Por ejemplo: 3.700"));
        verifyNoInteractions(edicion);
    }

    @Test
    void cambiarElMontoDeUnaJuntadaEsUn409ConLaExplicacion() throws Exception {
        when(edicion.editar(any(Long.class), any(), any(), any(), any(), any(), any())).thenThrow(
                new MovimientoEspecialException("El monto de una juntada es tu parte"));

        mvc.perform(put("/api/movimientos/7").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("El monto de una juntada es tu parte"));
    }

    @Test
    void deleteBorraUnGastoComunPorDefecto() throws Exception {
        mvc.perform(delete("/api/movimientos/7")).andExpect(status().isNoContent());

        verify(edicion).borrar(7L, Alcance.GASTO);
    }

    @Test
    void deleteConAlcanceBorraLaJuntadaEntera() throws Exception {
        mvc.perform(delete("/api/movimientos/7").param("alcance", "JUNTADA")).andExpect(status().isNoContent());

        verify(edicion).borrar(7L, Alcance.JUNTADA);
    }

    @Test
    void unaCuotaSinElAlcanceEsUn409QueExplicaQueHacer() throws Exception {
        doThrow(new MovimientoEspecialException("Es la cuota 2 de 6: se cancela el plan desde esta cuota"))
                .when(edicion).borrar(7L, Alcance.GASTO);

        mvc.perform(delete("/api/movimientos/7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Es la cuota 2 de 6: se cancela el plan desde esta cuota"));
    }

    @Test
    void unoQueNoExisteEsUn404YUnAlcanceInventadoUn400() throws Exception {
        doThrow(new NoSuchElementException()).when(edicion).borrar(9L, Alcance.GASTO);

        mvc.perform(delete("/api/movimientos/9")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/movimientos/7").param("alcance", "TODO")).andExpect(status().isBadRequest());
    }
}
