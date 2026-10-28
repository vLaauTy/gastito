package ar.gastito.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.gastito.recibo.AnalisisRecibos;
import ar.gastito.recibo.ControlRecibo;
import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.ReciboService;
import ar.gastito.recibo.TipoRecibo;

@WebMvcTest(RecibosController.class)
@ActiveProfiles("test")
@Import(RecibosControllerTest.Config.class)
class RecibosControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            ZoneId zona = ZoneId.of("America/Argentina/Buenos_Aires");
            return Clock.fixed(LocalDateTime.of(2026, 11, 5, 10, 0).atZone(zona).toInstant(), zona);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReciboService recibos;

    @MockitoBean
    AnalisisRecibos analisis;

    private static ReciboService.VistaRecibo vista() {
        return new ReciboService.VistaRecibo(31L,
                new ReciboService.Cobro(77L, new BigDecimal("1030000.00"), LocalDateTime.of(2026, 10, 30, 12, 0),
                        false),
                "Empresa Ejemplo", YearMonth.of(2026, 10), TipoRecibo.SUELDO_MENSUAL, LocalDate.of(2026, 11, 3),
                new BigDecimal("1250000.00"), new BigDecimal("212500.00"), new BigDecimal("1037500.00"),
                List.of(new ReciboService.Renglon("Sueldo básico", GrupoConcepto.REMUNERATIVO,
                                new BigDecimal("1250000.00"), 1L),
                        new ReciboService.Renglon("Jubilación 11%", GrupoConcepto.DESCUENTO,
                                new BigDecimal("137500.00"), null)),
                null, true, new BigDecimal("1040000.00"),
                new ControlRecibo.Resultado(List.of(new ControlRecibo.Hallazgo(ControlRecibo.Tipo.NETO_DISTINTO,
                        "El neto del recibo es $1.037.500 y vos cobraste $1.030.000 (diferencia $7.500).")),
                        true, true));
    }

    @Test
    void elDetalleDeUnReciboConSusConceptosElCobroYLoRaro() throws Exception {
        when(recibos.vista(31L)).thenReturn(Optional.of(vista()));

        mvc.perform(get("/api/recibos/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.empleador").value("Empresa Ejemplo"))
                .andExpect(jsonPath("$.periodo").value("2026-10"))
                .andExpect(jsonPath("$.tipo").value("SUELDO_MENSUAL"))
                .andExpect(jsonPath("$.tipoNombre").value("Sueldo"))
                .andExpect(jsonPath("$.neto").value(1037500.00))
                .andExpect(jsonPath("$.aviso").doesNotExist())
                .andExpect(jsonPath("$.conArchivo").value(true))
                .andExpect(jsonPath("$.cobro.movimientoId").value(77))
                .andExpect(jsonPath("$.cobro.monto").value(1030000.00))
                .andExpect(jsonPath("$.cobro.aMano").value(false))
                .andExpect(jsonPath("$.calculado").value(1040000.00))
                .andExpect(jsonPath("$.conCalculo").value(true))
                .andExpect(jsonPath("$.hallazgos.length()").value(1))
                .andExpect(jsonPath("$.hallazgos[0]").value(org.hamcrest.Matchers.startsWith("El neto del recibo")))
                .andExpect(jsonPath("$.conceptos.length()").value(2))
                .andExpect(jsonPath("$.conceptos[0].conceptoSueldoId").value(1))
                .andExpect(jsonPath("$.conceptos[1].descripcion").value("Jubilación 11%"))
                .andExpect(jsonPath("$.conceptos[1].grupo").value("DESCUENTO"))
                .andExpect(jsonPath("$.conceptos[1].grupoNombre").value("Descuento"))
                .andExpect(jsonPath("$.conceptos[1].importe").value(137500.00));
    }

    @Test
    void unReciboSinCobroTraElCobroNulo() throws Exception {
        ReciboService.VistaRecibo base = vista();
        when(recibos.vista(31L)).thenReturn(Optional.of(new ReciboService.VistaRecibo(base.id(), null,
                base.empleador(), base.periodo(), base.tipo(), base.fechaPago(), base.bruto(), base.descuentos(),
                base.neto(), base.conceptos(), null, true, null,
                new ControlRecibo.Resultado(List.of(), false, false))));

        mvc.perform(get("/api/recibos/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cobro").doesNotExist())
                .andExpect(jsonPath("$.calculado").doesNotExist())
                .andExpect(jsonPath("$.hallazgos.length()").value(0));
    }

    @Test
    void sePuedeVincularElReciboConUnSueldoCobrado() throws Exception {
        when(recibos.vincular(31L, 77L)).thenReturn(vista());

        mvc.perform(post("/api/recibos/31/vincular").contentType("application/json")
                        .content("{\"movimientoId\": 77}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cobro.movimientoId").value(77));
    }

    @Test
    void vincularAUnIngresoQueNoSirveEsUnErrorDelUsuario() throws Exception {
        when(recibos.vincular(31L, 5L)).thenThrow(new IllegalArgumentException("Ese no es un ingreso guardado"));

        mvc.perform(post("/api/recibos/31/vincular").contentType("application/json")
                        .content("{\"movimientoId\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ese no es un ingreso guardado"));
    }

    @Test
    void sePuedeDescartarUnRecibo() throws Exception {
        mvc.perform(delete("/api/recibos/31")).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(recibos).descartar(31L);
    }

    @Test
    void unReciboQueNoExisteEs404() throws Exception {
        when(recibos.vista(5L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/recibos/5")).andExpect(status().isNotFound());
    }

    @Test
    void elArchivoSeSirveConSuTipoYSinCache(@TempDir Path carpeta) throws Exception {
        Path pdf = Files.writeString(carpeta.resolve("31.pdf"), "%PDF-falso");
        Path jpg = Files.writeString(carpeta.resolve("32.jpg"), "jpg-falso");
        when(recibos.archivo(31L)).thenReturn(Optional.of(pdf));
        when(recibos.archivo(32L)).thenReturn(Optional.of(jpg));

        mvc.perform(get("/api/recibos/31/archivo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string("%PDF-falso"));
        mvc.perform(get("/api/recibos/32/archivo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
    }

    @Test
    void sinArchivoEs404() throws Exception {
        when(recibos.archivo(9L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/recibos/9/archivo")).andExpect(status().isNotFound());
    }

    @Test
    void laSerieTrae12MesesPorOmisionYLasVariaciones() throws Exception {
        AnalisisRecibos.Serie serie = new AnalisisRecibos.Serie(YearMonth.of(2026, 11),
                List.of(new AnalisisRecibos.PuntoSerie(YearMonth.of(2026, 10), true, new BigDecimal("1250000.00"),
                        new BigDecimal("212500.00"), new BigDecimal("1037500.00")),
                        new AnalisisRecibos.PuntoSerie(YearMonth.of(2026, 11), false, null, null, null)),
                null, new AnalisisRecibos.Cambio(YearMonth.of(2026, 10), new BigDecimal("1037500.00"), null, null,
                        "SIN_DATOS"),
                new AnalisisRecibos.Cambio(YearMonth.of(2025, 11), null, null, null, "SIN_DATOS"));
        when(analisis.serie(YearMonth.of(2026, 11), 12)).thenReturn(serie);

        mvc.perform(get("/api/recibos/serie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasta").value("2026-11"))
                .andExpect(jsonPath("$.meses[0].bruto").value(1250000.00))
                .andExpect(jsonPath("$.meses[0].descuentos").value(212500.00))
                .andExpect(jsonPath("$.meses[0].neto").value(1037500.00))
                .andExpect(jsonPath("$.meses[1].conRecibo").value(false))
                .andExpect(jsonPath("$.meses[1].neto").doesNotExist())
                .andExpect(jsonPath("$.netoActual").doesNotExist())
                .andExpect(jsonPath("$.contraMesAnterior.direccion").value("SIN_DATOS"))
                .andExpect(jsonPath("$.contraMismoMesAnioAnterior.mes").value("2025-11"));
    }

    @Test
    void laSerieAceptaElMesFinalYLaCantidad() throws Exception {
        when(analisis.serie(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new AnalisisRecibos.Serie(YearMonth.of(2026, 10), List.of(), null, null, null));

        mvc.perform(get("/api/recibos/serie?hasta=2026-10&meses=6")).andExpect(status().isOk());
        org.mockito.Mockito.verify(analisis).serie(YearMonth.of(2026, 10), 6);
    }

    @Test
    void laCantidadDeMesesTieneUnTope() throws Exception {
        mvc.perform(get("/api/recibos/serie?meses=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/recibos/serie?meses=37")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/recibos/serie?hasta=octubre")).andExpect(status().isBadRequest());
    }
}
