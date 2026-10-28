package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import java.util.Map;
import java.util.Optional;

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

import ar.gastito.recibo.SeguimientoSueldo;
import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;

@WebMvcTest(SueldoController.class)
@ActiveProfiles("test")
@Import(SueldoControllerTest.Config.class)
class SueldoControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            ZoneId zona = ZoneId.of("America/Argentina/Buenos_Aires");
            return Clock.fixed(LocalDateTime.of(2026, 10, 27, 10, 0).atZone(zona).toInstant(), zona);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SueldoService sueldo;

    @MockitoBean
    SeguimientoSueldo seguimiento;

    private static final ConceptoSueldo BASICO = ConceptoSueldo.conId(1L,
            new ConceptoSueldo("Sueldo básico", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null, 1));
    private static final ConceptoSueldo AL_50 = ConceptoSueldo.conId(5L,
            new ConceptoSueldo("Horas al 50%", TipoConceptoSueldo.HORAS, 3, 2, 5));
    private static final ConceptoSueldo JUBILACION = ConceptoSueldo.conId(10L,
            new ConceptoSueldo("Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null, 10));

    private static EscalaSueldo escala() {
        return new EscalaSueldo(LocalDate.of(2026, 1, 1), new BigDecimal("3000"), Map.of(
                BASICO.getId(), new BigDecimal("500000"), JUBILACION.getId(), new BigDecimal("11")));
    }

    private static CalculadoraSueldo.Resultado resultado() {
        return CalculadoraSueldo.calcular(List.of(BASICO, AL_50, JUBILACION), escala(),
                Map.of(AL_50.getId(), new BigDecimal("10")), Map.of());
    }

    @Test
    void losConceptosSalenConSuTipoYSuFactor() throws Exception {
        when(sueldo.listarConceptos()).thenReturn(List.of(BASICO, AL_50));

        mvc.perform(get("/api/sueldo/conceptos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("Sueldo básico"))
                .andExpect(jsonPath("$[0].tipo").value("REMUNERATIVO_FIJO"))
                .andExpect(jsonPath("$[0].factor").doesNotExist())
                .andExpect(jsonPath("$[1].tipo").value("HORAS"))
                .andExpect(jsonPath("$[1].factorNum").value(3))
                .andExpect(jsonPath("$[1].factorDen").value(2))
                .andExpect(jsonPath("$[1].factor").value(1.5));
    }

    @Test
    void sePuedeCrearUnConcepto() throws Exception {
        when(sueldo.guardarConcepto(null, "Premio", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null, null, true))
                .thenReturn(ConceptoSueldo.conId(20L,
                        new ConceptoSueldo("Premio", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null, 14)));

        mvc.perform(post("/api/sueldo/conceptos").contentType("application/json")
                        .content("{\"nombre\":\"Premio\",\"tipo\":\"REMUNERATIVO_FIJO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.nombre").value("Premio"));
    }

    @Test
    void unTipoDeConceptoInvalidoEsUnErrorDelUsuario() throws Exception {
        mvc.perform(post("/api/sueldo/conceptos").contentType("application/json")
                        .content("{\"nombre\":\"Premio\",\"tipo\":\"NADA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Elegí el tipo de concepto"));
    }

    @Test
    void lasEscalasSalenDeLaMasNuevaALaMasVieja() throws Exception {
        when(sueldo.listarEscalas()).thenReturn(List.of(escala()));

        mvc.perform(get("/api/sueldo/escalas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vigenteDesde").value("2026-01-01"))
                .andExpect(jsonPath("$[0].valorHora").value(3000))
                .andExpect(jsonPath("$[0].valores['1']").value(500000));
    }

    @Test
    void crearUnaEscalaMandaLosValoresPorConcepto() throws Exception {
        when(sueldo.crearEscala(any(), any(), any())).thenReturn(escala());

        mvc.perform(post("/api/sueldo/escalas").contentType("application/json")
                        .content("{\"vigenteDesde\":\"2026-07-01\",\"valorHora\":3300,"
                                + "\"valores\":{\"1\":550000,\"10\":11}}"))
                .andExpect(status().isOk());
        verify(sueldo).crearEscala(LocalDate.of(2026, 7, 1), new BigDecimal("3300"),
                Map.of(1L, new BigDecimal("550000"), 10L, new BigDecimal("11")));
    }

    @Test
    void unaEscalaRepetidaEsUnErrorDelUsuario() throws Exception {
        when(sueldo.crearEscala(any(), any(), any())).thenThrow(new IllegalArgumentException(
                "Ya hay una escala desde esa fecha: editala en lugar de crear otra"));

        mvc.perform(post("/api/sueldo/escalas").contentType("application/json")
                        .content("{\"vigenteDesde\":\"2026-01-01\",\"valorHora\":3000,\"valores\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ya hay una escala desde esa fecha: editala en lugar de crear otra"));
    }

    @Test
    void sePuedeEditarUnaEscala() throws Exception {
        when(sueldo.editarEscala(eq(3L), any(), any(), any())).thenReturn(escala());

        mvc.perform(put("/api/sueldo/escalas/3").contentType("application/json")
                        .content("{\"vigenteDesde\":\"2026-01-01\",\"valorHora\":3000,\"valores\":{\"1\":520000}}"))
                .andExpect(status().isOk());
        verify(sueldo).editarEscala(3L, LocalDate.of(2026, 1, 1), new BigDecimal("3000"),
                Map.of(1L, new BigDecimal("520000")));
    }

    @Test
    void calcularEnVivoDevuelveLasLineasYLosTotalesSinGuardar() throws Exception {
        when(sueldo.calcular(eq(YearMonth.of(2026, 10)), any(), any())).thenReturn(new SueldoService.VistaCalculo(
                YearMonth.of(2026, 10), false, 1L, LocalDate.of(2026, 1, 1), resultado()));

        mvc.perform(post("/api/sueldo/calcular").contentType("application/json")
                        .content("{\"mes\":\"2026-10\",\"horas\":{\"5\":10},\"ajustes\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existe").value(true))
                .andExpect(jsonPath("$.guardado").value(false))
                .andExpect(jsonPath("$.mes").value("2026-10"))
                .andExpect(jsonPath("$.escalaDesde").value("2026-01-01"))
                .andExpect(jsonPath("$.bruto").value(545000.00))
                .andExpect(jsonPath("$.descuentos").value(59950.00))
                .andExpect(jsonPath("$.netoTotal").value(485050.00))
                .andExpect(jsonPath("$.lineas.length()").value(3))
                .andExpect(jsonPath("$.lineas[1].nombre").value("Horas al 50%"))
                .andExpect(jsonPath("$.lineas[1].cantidad").value(10))
                .andExpect(jsonPath("$.lineas[1].importe").value(45000.00));
        verify(sueldo).calcular(YearMonth.of(2026, 10), Map.of(5L, new BigDecimal("10")), Map.of());
    }

    @Test
    void sinEscalaElCalculoEsUnErrorDelUsuario() throws Exception {
        when(sueldo.calcular(any(), any(), any())).thenThrow(new IllegalArgumentException("No hay una escala"));

        mvc.perform(post("/api/sueldo/calcular").contentType("application/json")
                        .content("{\"mes\":\"2026-10\",\"horas\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No hay una escala"));
    }

    @Test
    void unMesMalEscritoEsUnErrorDelUsuario() throws Exception {
        mvc.perform(post("/api/sueldo/calcular").contentType("application/json")
                        .content("{\"mes\":\"octubre\",\"horas\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El mes tiene que ser AAAA-MM"));
    }

    @Test
    void guardarElCalculoLoMarcaGuardado() throws Exception {
        when(sueldo.guardar(any(), any(), any())).thenReturn(new SueldoService.VistaCalculo(
                YearMonth.of(2026, 10), true, 1L, LocalDate.of(2026, 1, 1), resultado()));

        mvc.perform(post("/api/sueldo/calculo").contentType("application/json")
                        .content("{\"mes\":\"2026-10\",\"horas\":{\"5\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guardado").value(true));
    }

    @Test
    void elCalculoGuardadoDelMesVieneConSusLineas() throws Exception {
        CalculoSueldo guardado = new CalculoSueldo(YearMonth.of(2026, 10), 1L, resultado(),
                LocalDateTime.of(2026, 10, 27, 9, 0));
        when(sueldo.calculoDelMes(YearMonth.of(2026, 10))).thenReturn(Optional.of(guardado));
        when(sueldo.listarEscalas()).thenReturn(List.of(escalaConId()));

        mvc.perform(get("/api/sueldo/calculo?mes=2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existe").value(true))
                .andExpect(jsonPath("$.guardado").value(true))
                .andExpect(jsonPath("$.netoTotal").value(485050.00))
                .andExpect(jsonPath("$.lineas[2].tipo").value("DESCUENTO_PORCENTAJE"));
    }

    private static EscalaSueldo escalaConId() {
        return escala();
    }

    @Test
    void unMesSinCalculoDiceQueNoExiste() throws Exception {
        when(sueldo.calculoDelMes(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/sueldo/calculo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existe").value(false))
                .andExpect(jsonPath("$.mes").value("2026-10"))
                .andExpect(jsonPath("$.lineas.length()").value(0));
    }

    @Test
    void elSeguimientoTraLosDoceMesesPorOmision() throws Exception {
        SueldoService.Comparacion cobradoVsCalculado = new SueldoService.Comparacion(YearMonth.of(2026, 10),
                new BigDecimal("1114400"), new BigDecimal("1110000"), new BigDecimal("-4400"),
                new BigDecimal("-0.4"), false);
        when(seguimiento.meses(YearMonth.of(2026, 10), 12)).thenReturn(List.of(new SeguimientoSueldo.Mes(
                YearMonth.of(2026, 10), new BigDecimal("1114400"), new BigDecimal("1110000"), cobradoVsCalculado,
                7L, new BigDecimal("1110000"), null, null, true, List.of("Algo raro"), false)));

        mvc.perform(get("/api/sueldo/meses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mes").value("2026-10"))
                .andExpect(jsonPath("$[0].calculado").value(1114400))
                .andExpect(jsonPath("$[0].cobradoVsCalculado.diferencia").value(-4400))
                .andExpect(jsonPath("$[0].cobradoVsCalculado.fuera").value(false))
                .andExpect(jsonPath("$[0].reciboId").value(7))
                .andExpect(jsonPath("$[0].reciboVsCobrado").doesNotExist())
                .andExpect(jsonPath("$[0].avisos[0]").value("Algo raro"));
    }

    @Test
    void laCantidadDeMesesTieneUnTope() throws Exception {
        mvc.perform(get("/api/sueldo/meses?meses=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sueldo/meses?meses=37")).andExpect(status().isBadRequest());
    }
}
