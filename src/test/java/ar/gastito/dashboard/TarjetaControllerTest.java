package ar.gastito.dashboard;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.EstadoResumenTarjeta;
import ar.gastito.tarjeta.VistaResumenTarjeta;

@WebMvcTest(TarjetaController.class)
@ActiveProfiles("test")
@EnableConfigurationProperties(DashboardProperties.class)
class TarjetaControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CicloTarjetaService ciclo;

    private static VistaResumenTarjeta cerrado(String totalReal, String diferencia) {
        return new VistaResumenTarjeta(7L, YearMonth.of(2026, 10), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11), false, EstadoResumenTarjeta.CERRADO,
                new BigDecimal("180000.00"), new BigDecimal("20000.00"), new BigDecimal("200000.00"),
                totalReal == null ? null : new BigDecimal(totalReal),
                diferencia == null ? null : new BigDecimal(diferencia), "MasterCard Gold", "Galicia",
                List.of(new VistaResumenTarjeta.Linea(1L, LocalDate.of(2026, 10, 20).atTime(12, 0), "Remera", "Ropa",
                        null, new BigDecimal("180000.00"), null, null, false)));
    }

    @Test
    void sinCierreCargadoNoEstaConfigurada() throws Exception {
        when(ciclo.panel()).thenReturn(Optional.empty());

        mvc.perform(get("/api/tarjeta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurada").value(false));
    }

    @Test
    void elPanelTraeLosTotalesHechos() throws Exception {
        when(ciclo.panel()).thenReturn(Optional.of(new CicloTarjetaService.Panel("MasterCard Gold", "Galicia",
                List.of(cerrado(null, null)), cerrado(null, null), List.of(), BigDecimal.ZERO, List.of())));

        mvc.perform(get("/api/tarjeta"))
                .andExpect(jsonPath("$.configurada").value(true))
                .andExpect(jsonPath("$.panel.porPagar[0].vencimiento").value("2026-11-11"))
                .andExpect(jsonPath("$.panel.porPagar[0].cargado").value(200000.0))
                .andExpect(jsonPath("$.panel.porPagar[0].aDebitar").value(200000.0))
                .andExpect(jsonPath("$.panel.porPagar[0].cantidadCompras").value(1))
                .andExpect(jsonPath("$.panel.porPagar[0].detalle[0].descripcion").value("Remera"));
    }

    @Test
    void cargarLasFechas() throws Exception {
        when(ciclo.cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11))).thenReturn(
                new CicloTarjetaService.FechasCargadas(cerrado(null, null), null));

        mvc.perform(post("/api/tarjeta/fechas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cierre\":\"2026-11-26\",\"vencimiento\":\"2026-12-11\"}"))
                .andExpect(status().isOk());

        verify(ciclo).cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));
    }

    @Test
    void sinVencimientoEsUnError() throws Exception {
        mvc.perform(post("/api/tarjeta/fechas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cierre\":\"2026-11-26\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ciclo);
    }

    @Test
    void elTotalDelResumenDiceLaDiferencia() throws Exception {
        when(ciclo.cargarTotal(7L, new BigDecimal("250000"))).thenReturn(new CicloTarjetaService.TotalCargado(
                cerrado("250000", "50000.00"), new BigDecimal("250000"), new BigDecimal("200000.00"),
                new BigDecimal("50000.00")));

        mvc.perform(post("/api/tarjeta/7/total").contentType(MediaType.APPLICATION_JSON).content("{\"total\":250000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diferencia").value(50000.0))
                .andExpect(jsonPath("$.coincide").value(false));
    }
}
