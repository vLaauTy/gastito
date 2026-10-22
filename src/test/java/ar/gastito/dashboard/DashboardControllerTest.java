package ar.gastito.dashboard;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;

@WebMvcTest(DashboardController.class)
@ActiveProfiles("test")
@Import(DashboardControllerTest.Reloj.class)
class DashboardControllerTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 21, 16, 42);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DashboardService dashboard;

    @MockitoBean
    ConsultaMovimientos movimientos;

    @MockitoBean
    IngresoService ingresos;

    @MockitoBean
    CatalogoService catalogos;

    private static BigDecimal pesos(String monto) {
        return new BigDecimal(monto).setScale(2);
    }

    // --- Resumen ---

    @Test
    void resumenConLaPlataComoNumeroConDosDecimales() throws Exception {
        when(dashboard.resumen(OCTUBRE)).thenReturn(new DashboardService.Resumen(OCTUBRE, true, 21,
                pesos("186400"), 63, pesos("8876.19"),
                new DashboardService.Comparacion(YearMonth.of(2026, 9), pesos("172600"), 21, false, 8,
                        DashboardService.Direccion.SUBE),
                new DashboardService.Ingresos(pesos("405000"), pesos("380000"), pesos("0"), pesos("25000"), 1),
                new DashboardService.Disponible(pesos("218600"), 46)));

        mvc.perform(get("/api/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-10"))
                .andExpect(jsonPath("$.mesActual").value(true))
                .andExpect(jsonPath("$.gastado").value(186400.00))
                .andExpect(jsonPath("$.cantidadGastos").value(63))
                .andExpect(jsonPath("$.comparacion.mes").value("2026-09"))
                .andExpect(jsonPath("$.comparacion.variacionPorcentaje").value(8))
                .andExpect(jsonPath("$.comparacion.direccion").value("SUBE"))
                .andExpect(jsonPath("$.ingresos.sueldo").value(380000.00))
                .andExpect(jsonPath("$.disponible.porcentajeGastado").value(46))
                .andExpect(content().string(containsString("\"gastado\":186400.00")))
                .andExpect(content().string(containsString("\"promedioDiario\":8876.19")));
    }

    @Test
    void elMesSeElige() throws Exception {
        when(dashboard.porDia(YearMonth.of(2026, 9))).thenReturn(
                new DashboardService.PorDia(YearMonth.of(2026, 9), List.of(), pesos("0"), null));

        mvc.perform(get("/api/gastos/por-dia").param("mes", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-09"));
        verify(dashboard).porDia(YearMonth.of(2026, 9));
    }

    @Test
    void unMesMalEscritoEsUnError400() throws Exception {
        mvc.perform(get("/api/resumen").param("mes", "octubre"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Parámetro inválido: mes"));
        verifyNoInteractions(dashboard);
    }

    @Test
    void porBilleteraConColorYTipo() throws Exception {
        when(dashboard.porBilletera(OCTUBRE)).thenReturn(List.of(new DashboardService.Billetera("sin-especificar",
                DashboardService.TipoBilletera.SIN_ESPECIFICAR, "Sin especificar", "#D5DBD2", pesos("5600"), 2,
                new BigDecimal("3.0"))));

        mvc.perform(get("/api/gastos/por-billetera"))
                .andExpect(jsonPath("$[0].tipo").value("SIN_ESPECIFICAR"))
                .andExpect(jsonPath("$[0].color").value("#D5DBD2"))
                .andExpect(jsonPath("$[0].porcentaje").value(3.0));
    }

    // --- Movimientos ---

    @Test
    void movimientosPasaLosFiltrosYLaPagina() throws Exception {
        when(movimientos.buscar(any(), anyInt(), anyInt())).thenReturn(new ConsultaMovimientos.Pagina(List.of(
                new ConsultaMovimientos.Fila(7L, LocalDateTime.of(2026, 10, 21, 16, 42), "Pan de pancho y papas", 2L,
                        "Supermercado", "🛒", "Efectivo", false, "AUDIO", pesos("3700"))), 1, 10, 11, 2));

        mvc.perform(get("/api/movimientos").param("mes", "2026-10").param("categoria", "2").param("medioPago", "1")
                .param("origen", "AUDIO").param("q", "pan").param("pagina", "1").param("tamanio", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].fechaHora").value("2026-10-21T16:42:00"))
                .andExpect(jsonPath("$.elementos[0].origen").value("AUDIO"))
                .andExpect(jsonPath("$.elementos[0].monto").value(3700.00))
                .andExpect(jsonPath("$.totalElementos").value(11))
                .andExpect(jsonPath("$.totalPaginas").value(2));
        verify(movimientos).buscar(new ConsultaMovimientos.Filtros(OCTUBRE, 2L, 1L, Origen.AUDIO, "pan"), 1, 10);
    }

    @Test
    void unOrigenQueNoExisteEsUnError400() throws Exception {
        mvc.perform(get("/api/movimientos").param("origen", "FAX")).andExpect(status().isBadRequest());
    }

    @Test
    void unTamanioFueraDeRangoEsUnError400() throws Exception {
        when(movimientos.buscar(any(), anyInt(), eq(500)))
                .thenThrow(new IllegalArgumentException("Página o tamaño inválidos"));

        mvc.perform(get("/api/movimientos").param("tamanio", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Página o tamaño inválidos"));
    }

    @Test
    void csvSeDescargaConNombreDelMes() throws Exception {
        when(movimientos.csv(OCTUBRE)).thenReturn("﻿Fecha;Monto\r\n".getBytes(StandardCharsets.UTF_8));

        mvc.perform(get("/api/movimientos.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", containsString("gastito-2026-10.csv")))
                .andExpect(content().bytes("﻿Fecha;Monto\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    // --- Catálogo ---

    @Test
    void catalogoConCategoriasMediosCuentasYClasesDeIngreso() throws Exception {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(new Categoria(1L, "Kiosco", "kiosco", null, "🍬")),
                List.of(new MedioPago("Efectivo", "efectivo")), List.of(new Cuenta("Galicia", "galicia")),
                Optional.empty()));

        mvc.perform(get("/api/catalogo"))
                .andExpect(jsonPath("$.categorias[0].nombre").value("Kiosco"))
                .andExpect(jsonPath("$.categorias[0].emoji").value("🍬"))
                .andExpect(jsonPath("$.mediosPago[0].nombre").value("Efectivo"))
                .andExpect(jsonPath("$.cuentas[0].nombre").value("Galicia"))
                .andExpect(jsonPath("$.clasesIngreso[*].clave").value(
                        contains("SUELDO", "AGUINALDO", "EXTRA")));
    }

    // --- Ingresos ---

    @Test
    void cargarUnIngreso() throws Exception {
        Movimiento sueldo = Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal("380000"), "Sueldo", null, null,
                LocalDate.of(2026, 10, 1).atStartOfDay(), Origen.DASHBOARD, null, AHORA);
        ReflectionTestUtils.setField(sueldo, "id", 40L);
        when(ingresos.cargar(any(), any(), any(), any(), any())).thenReturn(sueldo);

        mvc.perform(post("/api/ingresos").contentType(MediaType.APPLICATION_JSON).content("""
                {"clase": "sueldo", "monto": "380 mil", "descripcion": "Sueldo", "cuentaId": 1, "fecha": "2026-10-01"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/ingresos/40"))
                .andExpect(jsonPath("$.claseNombre").value("Sueldo"))
                .andExpect(jsonPath("$.monto").value(380000.00));
        verify(ingresos).cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), "Sueldo", 1L, LocalDate.of(2026, 10, 1));
    }

    @Test
    void sinFechaEsHoy() throws Exception {
        Movimiento extra = Movimiento.ingreso(ClaseIngreso.EXTRA, new BigDecimal("25000"), null, null, null, AHORA,
                Origen.DASHBOARD, null, AHORA);
        when(ingresos.cargar(any(), any(), any(), any(), any())).thenReturn(extra);

        mvc.perform(post("/api/ingresos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clase\": \"EXTRA\", \"monto\": \"25.000\"}"))
                .andExpect(status().isCreated());
        verify(ingresos).cargar(ClaseIngreso.EXTRA, new BigDecimal("25000"), null, null, LocalDate.of(2026, 10, 21));
    }

    @Test
    void ingresoConMontoOClaseQueNoSirvenEsUnError400() throws Exception {
        mvc.perform(post("/api/ingresos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clase\": \"SUELDO\", \"monto\": \"mucho\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("monto")));
        mvc.perform(post("/api/ingresos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clase\": \"PREMIO\", \"monto\": \"100\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("tipo de ingreso")));
        verifyNoInteractions(ingresos);
    }

    @Test
    void cargarIngresoSoloAceptaJson() throws Exception {
        // Un formulario de otra página web no puede mandar JSON sin permiso del navegador (CORS).
        mvc.perform(post("/api/ingresos").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("clase=SUELDO&monto=1"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(ingresos);
    }

    @Test
    void borrarUnIngreso() throws Exception {
        mvc.perform(delete("/api/ingresos/40")).andExpect(status().isNoContent());
        verify(ingresos).anular(40L);
    }

    @Test
    void borrarAlgoQueNoExisteONoEsUnIngreso() throws Exception {
        doThrow(new NoSuchElementException("x")).when(ingresos).anular(41L);
        doThrow(new IllegalStateException("x")).when(ingresos).anular(42L);

        mvc.perform(delete("/api/ingresos/41")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/ingresos/42")).andExpect(status().isConflict());
    }

    @Test
    void ingresosPorMesSonSeisPorDefecto() throws Exception {
        when(dashboard.ingresosPorMes(OCTUBRE, 6)).thenReturn(List.of());

        mvc.perform(get("/api/ingresos/por-mes")).andExpect(status().isOk());
        mvc.perform(get("/api/ingresos/por-mes").param("meses", "99")).andExpect(status().isBadRequest());
        verify(dashboard).ingresosPorMes(OCTUBRE, 6);
    }
}
