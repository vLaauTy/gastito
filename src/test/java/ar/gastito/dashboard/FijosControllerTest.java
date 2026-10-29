package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.PagoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.movimiento.Origen;

@WebMvcTest(FijosController.class)
@ActiveProfiles("test")
@Import(FijosControllerTest.Reloj.class)
class FijosControllerTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 21, 16, 42);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);
    static final Categoria SERVICIOS = new Categoria(5L, "Servicios", "luz,gas", null, "💡");

    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GastoFijoService fijos;

    @MockitoBean
    PagoFijoService pagos;

    @MockitoBean
    CatalogoService catalogos;

    @Test
    void elChecklistTraeElTotalCargadoYLoQueFalta() throws Exception {
        GastoFijoService.ItemChecklist cargado = new GastoFijoService.ItemChecklist(1L, "Seguro", "🛡️",
                GastoFijoService.EstadoChecklist.CARGADO, new BigDecimal("32900.00"), LocalDate.of(2026, 10, 4),
                null, null, LocalDate.of(2026, 10, 4), true, 70L, false, null);
        GastoFijoService.ItemChecklist falta = new GastoFijoService.ItemChecklist(2L, "Luz", "💡",
                GastoFijoService.EstadoChecklist.FALTA, null, null, new BigDecimal("28000.00"), null,
                LocalDate.of(2026, 10, 27), false, null, false, new BigDecimal("28000.00"));
        when(fijos.checklist(OCTUBRE)).thenReturn(List.of(cargado, falta));

        mvc.perform(get("/api/fijos/checklist").param("mes", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-10"))
                .andExpect(jsonPath("$.cargado").value(32900.00))
                .andExpect(jsonPath("$.faltan").value(1))
                .andExpect(jsonPath("$.items[0].nombre").value("Seguro"))
                .andExpect(jsonPath("$.items[0].estado").value("CARGADO"))
                .andExpect(jsonPath("$.items[1].estado").value("FALTA"))
                .andExpect(jsonPath("$.items[1].aproximado").value(28000.00))
                .andExpect(jsonPath("$.items[0].movimientoId").value(70))
                .andExpect(jsonPath("$.estimado").value(0));
    }

    @Test
    void sinMesUsaElActual() throws Exception {
        when(fijos.checklist(OCTUBRE)).thenReturn(List.of());

        mvc.perform(get("/api/fijos/checklist")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-10")).andExpect(jsonPath("$.cargado").value(0));
    }

    @Test
    void cargarCreaElGastoComoElBot() throws Exception {
        mvc.perform(post("/api/fijos/2/cargar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monto\": 31000}"))
                .andExpect(status().isNoContent());

        verify(fijos).cargarManual(eq(2L), eq(new BigDecimal("31000")), any(), eq(Origen.DASHBOARD), eq(null));
    }

    @Test
    void pagadoTildaConElEstimadoYCorregirCambiaElMonto() throws Exception {
        mvc.perform(post("/api/fijos/2/pagado").param("mes", "2026-10")).andExpect(status().isNoContent());
        verify(pagos).pagado(2L, OCTUBRE, Origen.DASHBOARD);

        mvc.perform(put("/api/fijos/cobros/70/monto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monto\": 87500}"))
                .andExpect(status().isNoContent());
        verify(pagos).corregirMonto(70L, new BigDecimal("87500"));
    }

    @Test
    void pagadoSobreUnMesYaCargadoEsUnConflicto() throws Exception {
        when(pagos.pagado(2L, OCTUBRE, Origen.DASHBOARD))
                .thenThrow(new IllegalStateException("Ya cargaste Luz este mes"));

        mvc.perform(post("/api/fijos/2/pagado").param("mes", "2026-10")).andExpect(status().is4xxClientError());
    }

    @Test
    void esteMesNoYDeshacer() throws Exception {
        mvc.perform(post("/api/fijos/2/este-mes-no").param("mes", "2026-10")).andExpect(status().isNoContent());
        verify(fijos).esteMesNoDelPeriodo(2L, OCTUBRE);

        mvc.perform(post("/api/fijos/2/deshacer").param("mes", "2026-10")).andExpect(status().isNoContent());
        verify(fijos).deshacer(2L, OCTUBRE);
    }

    @Test
    void elHistorialTraeLosUltimosMeses() throws Exception {
        when(fijos.historial(2L)).thenReturn(
                List.of(new GastoFijoService.MesHistorial(YearMonth.of(2026, 9), new BigDecimal("28500.00"))));

        mvc.perform(get("/api/fijos/2/historial")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mes").value("2026-09"))
                .andExpect(jsonPath("$[0].monto").value(28500.00));
    }

    @Test
    void elListadoAbmTraeLosIdsDelCatalogo() throws Exception {
        GastoFijoService.VistaAbm abm = new GastoFijoService.VistaAbm(2L, "Luz", new BigDecimal("28000.00"),
                SERVICIOS, null, null, null, 27, Frecuencia.MENSUAL, true, "edesur", false, true);
        when(fijos.todosParaAbm()).thenReturn(List.of(abm));

        mvc.perform(get("/api/fijos")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Luz"))
                .andExpect(jsonPath("$[0].categoriaId").value(5))
                .andExpect(jsonPath("$[0].proponerMonto").value(false))
                .andExpect(jsonPath("$[0].tarjeta").value(false));
    }

    @Test
    void laAltaResuelveLaCategoriaPorId() throws Exception {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(SERVICIOS), List.of(), List.of(), Optional.empty()));
        VistaFijo creado = new VistaFijo(9L, "Luz", new BigDecimal("28000.00"), new BigDecimal("28000.00"),
                "Servicios", "💡", null, 27, Frecuencia.MENSUAL, true, "edesur", false, true,
                LocalDate.of(2026, 10, 27));
        when(fijos.alta(any())).thenReturn(creado);

        mvc.perform(post("/api/fijos").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Luz","monto":28000,"categoriaId":5,"diaDelMes":27,"frecuencia":"MENSUAL",
                 "montoVariable":true,"alias":"edesur","proponerMonto":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Luz"));

        org.mockito.ArgumentCaptor<GastoFijoService.NuevoFijo> captor =
                org.mockito.ArgumentCaptor.forClass(GastoFijoService.NuevoFijo.class);
        verify(fijos).alta(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().categoria()).isEqualTo(SERVICIOS);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().alias()).isEqualTo("edesur");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().proponerMonto()).isFalse();
    }

    @Test
    void editarCambiaLosDatosDelFijo() throws Exception {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(SERVICIOS), List.of(), List.of(), Optional.empty()));
        VistaFijo editado = new VistaFijo(9L, "Luz", new BigDecimal("30000.00"), new BigDecimal("30000.00"),
                "Servicios", "💡", null, 28, Frecuencia.MENSUAL, true, "edesur,factura de luz", false, true,
                LocalDate.of(2026, 10, 28));
        when(fijos.editar(eq(9L), any())).thenReturn(editado);

        mvc.perform(put("/api/fijos/9").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Luz","monto":30000,"categoriaId":5,"diaDelMes":28,"frecuencia":"MENSUAL",
                 "montoVariable":true,"alias":"edesur,factura de luz","proponerMonto":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaDelMes").value(28));
    }

    @Test
    void laBajaLlamaAlServicio() throws Exception {
        mvc.perform(delete("/api/fijos/7")).andExpect(status().isNoContent());
        verify(fijos).darDeBaja(7L);
    }
}
