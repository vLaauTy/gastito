package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.viaje.AhorroViaje;
import ar.gastito.viaje.PanelViaje;
import ar.gastito.viaje.ViajeInvalidoException;
import ar.gastito.viaje.ViajeService;

@WebMvcTest(ViajesController.class)
@ActiveProfiles("test")
@EnableConfigurationProperties(DashboardProperties.class)
class ViajesControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ViajeService viajes;

    @MockitoBean
    CatalogoService catalogos;

    private static PanelViaje bariloche() {
        return new PanelViaje(3L, "Bariloche", "🏔", LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 17), 8, 2, null,
                null, PanelViaje.PROXIMO, new BigDecimal("263000.00"), new BigDecimal("400000.00"), 1,
                new BigDecimal("663000.00"), null, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new PanelViaje.Pendiente(10L, "Hospedaje", new BigDecimal("400000.00"), null, false, null,
                        null)),
                List.of(), List.of(), List.of(), List.of(),
                new AhorroViaje(new BigDecimal("400000.00"), BigDecimal.ZERO, new BigDecimal("400000.00"), 2,
                        new BigDecimal("200000.00")));
    }

    @Test
    void losViajesLleganConLosTotalesHechos() throws Exception {
        when(viajes.todos()).thenReturn(List.of(bariloche()));

        mvc.perform(get("/api/viajes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Bariloche"))
                .andExpect(jsonPath("$[0].pagado").value(263000.00))
                .andExpect(jsonPath("$[0].totalEstimado").value(663000.00))
                .andExpect(jsonPath("$[0].presupuesto").doesNotExist())
                .andExpect(jsonPath("$[0].ahorro.porMes").value(200000.00))
                .andExpect(jsonPath("$[0].pendientes[0].descripcion").value("Hospedaje"));
    }

    @Test
    void crearUnViajeSinPresupuesto() throws Exception {
        when(viajes.crear(any())).thenReturn(bariloche());

        mvc.perform(post("/api/viajes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Bariloche\",\"desde\":\"2027-01-10\",\"hasta\":\"2027-01-17\",\"personas\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3));
        verify(viajes).crear(new ViajeService.Datos("Bariloche", null, LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 17), 2, null, null));
    }

    @Test
    void pagarUnPendienteSinMedio() throws Exception {
        when(viajes.pagarPendiente(eq(10L), any(), eq(5L), isNull(), isNull(), any())).thenReturn(bariloche());

        mvc.perform(post("/api/viajes/pendientes/10/pagar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"monto\":380000,\"categoriaId\":5,\"fecha\":\"2026-11-06\"}"))
                .andExpect(status().isOk());
        verify(viajes).pagarPendiente(10L, new BigDecimal("380000"), 5L, null, null, LocalDate.of(2026, 11, 6));
    }

    @Test
    void unViajeInvalidoDiceQuePasa() throws Exception {
        when(viajes.crear(any())).thenThrow(new ViajeInvalidoException("El viaje termina antes de empezar"));

        mvc.perform(post("/api/viajes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Bariloche\",\"desde\":\"2027-01-17\",\"hasta\":\"2027-01-10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El viaje termina antes de empezar"));
    }
}
