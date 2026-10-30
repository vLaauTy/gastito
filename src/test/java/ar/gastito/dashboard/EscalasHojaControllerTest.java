package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.sueldo.escala.HojaEscala;
import ar.gastito.sueldo.escala.ImportacionEscala;
import ar.gastito.sueldo.escala.ImportacionesPendientes;
import ar.gastito.sueldo.escala.LectorExcelEscala;
import ar.gastito.sueldo.escala.PlantillaEscala;

@WebMvcTest(EscalasHojaController.class)
@ActiveProfiles("test")
@Import(EscalasHojaControllerTest.Config.class)
class EscalasHojaControllerTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            ZoneId zona = ZoneId.of("America/Argentina/Buenos_Aires");
            return Clock.fixed(LocalDateTime.of(2026, 10, 27, 10, 0).atZone(zona).toInstant(), zona);
        }

        @Bean
        ImportacionesPendientes pendientes(Clock clock) {
            return new ImportacionesPendientes(clock);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LectorExcelEscala lector;

    @MockitoBean
    ImportacionEscala importacion;

    @MockitoBean
    PlantillaEscala plantilla;

    private static final MockMultipartFile EXCEL = new MockMultipartFile("archivo", "escala.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2});

    private static ImportacionEscala.Propuesta propuesta() {
        ImportacionEscala.Valor basico = new ImportacionEscala.Valor(1L, "Sueldo básico",
                ImportacionEscala.Clase.MONTO, new BigDecimal("460000.00"), new BigDecimal("500000.00"),
                new BigDecimal("8.7"));
        return new ImportacionEscala.Propuesta(List.of(new ImportacionEscala.MesPropuesto(OCT,
                new BigDecimal("3250.00"), Map.of(1L, new BigDecimal("500000.00")), List.of(basico), List.of(),
                new ImportacionEscala.Control(new BigDecimal("795200.00"), new BigDecimal("795200.00"),
                        BigDecimal.ZERO, true, new BigDecimal("20")), null)), List.of("ART. 13"));
    }

    @Test
    void elExcelSeLeeYDevuelveElResumenSinGuardar() throws Exception {
        HojaEscala hoja = new HojaEscala(List.of(new HojaEscala.Columna(OCT, List.of())));
        when(lector.leer(any())).thenReturn(hoja);
        when(importacion.proponer(hoja)).thenReturn(propuesta());

        mvc.perform(multipart("/api/sueldo/escalas/importar").file(EXCEL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meses[0].mes").value("2026-10"))
                .andExpect(jsonPath("$.meses[0].etiqueta").value("oct-26"))
                .andExpect(jsonPath("$.meses[0].leidos[0].variacion").value(8.7))
                .andExpect(jsonPath("$.meses[0].control.ok").value(true))
                .andExpect(jsonPath("$.ignoradas[0]").value("ART. 13"));
    }

    @Test
    void unaPlanillaSinMesPideElMes() throws Exception {
        when(lector.leer(any())).thenReturn(new HojaEscala(List.of(new HojaEscala.Columna(null, List.of()))));

        mvc.perform(multipart("/api/sueldo/escalas/importar").file(EXCEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La planilla no dice de qué mes es: elegí el mes y subila de nuevo"));
    }

    @Test
    void seGuardanLosMesesElegidosDeLaImportacion() throws Exception {
        HojaEscala hoja = new HojaEscala(List.of(new HojaEscala.Columna(OCT, List.of())));
        when(lector.leer(any())).thenReturn(hoja);
        ImportacionEscala.Propuesta propuesta = propuesta();
        when(importacion.proponer(hoja)).thenReturn(propuesta);
        when(importacion.guardar(eq(propuesta), eq(List.of(OCT))))
                .thenReturn(List.of(new ImportacionEscala.Guardada(OCT, 9L, false)));
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(multipart("/api/sueldo/escalas/importar").file(EXCEL))
                .andReturn().getResponse().getContentAsString(), "$.id").toString();

        mvc.perform(post("/api/sueldo/escalas/importaciones/" + id).contentType("application/json")
                .content("{\"meses\":[\"2026-10\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].etiqueta").value("oct-26"))
                .andExpect(jsonPath("$[0].reemplazada").value(false));
        verify(importacion).guardar(propuesta, List.of(OCT));
        // Ya se usó: otra vez no.
        mvc.perform(post("/api/sueldo/escalas/importaciones/" + id).contentType("application/json")
                .content("{\"meses\":[\"2026-10\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void laPlantillaSeDescargaComoExcel() throws Exception {
        when(plantilla.generar(OCT)).thenReturn(new byte[] {1});

        mvc.perform(get("/api/sueldo/escalas/plantilla"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"escala-2026-10.xlsx\""));
    }
}
