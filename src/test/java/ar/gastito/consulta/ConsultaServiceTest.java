package ar.gastito.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.resumen.RelojDePrueba;

/** La IA simulada: el mock "pide" herramientas llamando al ejecutor, como haría la IA. Hoy es el 25/10/2026. */
class ConsultaServiceTest {

    static final LocalDate HOY = LocalDate.of(2026, 10, 25);

    private final ClienteIa ia = mock(ClienteIa.class);
    private final HerramientasConsulta herramientas = mock(HerramientasConsulta.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final ar.gastito.recibo.AnalisisRecibos recibos = mock(ar.gastito.recibo.AnalisisRecibos.class);
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final ConsultaService consultas = new ConsultaService(ia, herramientas, recibos, catalogos, new Prompts(), json,
            new GastitoProperties(null, null, null, null, null, null,
                    new GastitoProperties.Consultas("claude-haiku-5-5", 1024, 3)),
            new RelojDePrueba(HOY.atTime(10, 0), java.time.ZoneId.of("America/Argentina/Buenos_Aires")));

    private final HerramientasConsulta.Total octubre = new HerramientasConsulta.Total(LocalDate.of(2026, 10, 1), HOY,
            new HerramientasConsulta.Filtros("Kiosco", null, null, null), new BigDecimal("14800.00"), 11);

    @BeforeEach
    void setUp() {
        when(catalogos.cargar()).thenReturn(CatalogoDePrueba.crear());
        when(herramientas.totalGastado(any(), any(), any())).thenReturn(octubre);
    }

    /** La IA pide estas herramientas, en orden, y después responde el texto. */
    private void laIa(String texto, Map<String, Object>... pedidos) {
        when(ia.conversar(anyString(), anyLong(), anyString(), anyString(), anyList(), anyInt(), any(),
                eq(PropositoIa.CONSULTA))).thenAnswer(inv -> {
                    ClienteIa.EjecutorHerramientas ejecutor = inv.getArgument(6);
                    List<ClienteIa.ResultadoHerramienta> resultados = new ArrayList<>();
                    for (Map<String, Object> pedido : pedidos) {
                        String nombre = (String) pedido.get("herramienta");
                        resultados.add(ejecutor.ejecutar(nombre, json.valueToTree(pedido)));
                    }
                    ultimosResultados = resultados;
                    return new ClienteIa.Conversacion(texto, pedidos.length, false, 7L);
                });
    }

    private List<ClienteIa.ResultadoHerramienta> ultimosResultados = List.of();

    @Test
    void unaRespuestaConLosNumerosDeLaHerramientaPasa() {
        laIa("En kiosco llevás $14.800 este mes, en 11 compras.",
                Map.of("herramienta", "totalGastado", "desde", "2026-10-01", "hasta", "2026-10-25",
                        "categoria", "Kiosco"));

        ConsultaService.Respuesta r = consultas.responder("¿Cuánto gasté en kiosco este mes?");

        assertThat(r.texto()).isEqualTo("En kiosco llevás $14.800 este mes, en 11 compras.");
        assertThat(r.descartado()).isFalse();
        assertThat(r.resultados()).containsExactly(octubre);
        assertThat(r.usoIaId()).isEqualTo(7L);
        verify(herramientas).totalGastado(LocalDate.of(2026, 10, 1), HOY,
                new HerramientasConsulta.Filtros("Kiosco", null, null, null));
    }

    @Test
    void unNumeroInventadoDescartaElTextoYQuedanLosResultados() {
        laIa("En kiosco llevás $14.800, un 20% más que septiembre.",
                Map.of("herramienta", "totalGastado", "desde", "2026-10-01", "hasta", "2026-10-25"));

        ConsultaService.Respuesta r = consultas.responder("¿Cuánto gasté en kiosco?");

        assertThat(r.texto()).isNull();
        assertThat(r.descartado()).isTrue();
        assertThat(r.resultados()).containsExactly(octubre);
    }

    @Test
    void losNumerosDeLaPreguntaTambienValen() {
        laIa("Del 3 al 10 de octubre gastaste $14.800.",
                Map.of("herramienta", "totalGastado", "desde", "2026-10-03", "hasta", "2026-10-10"));

        assertThat(consultas.responder("¿cuánto gasté del 3 al 10 de octubre?").texto()).isNotNull();
    }

    @Test
    void fechasInvalidasVuelvenComoErrorYNoSeConsulta() {
        laIa("No pude.",
                Map.of("herramienta", "totalGastado", "desde", "2026-02-30", "hasta", "2026-10-25"),
                Map.of("herramienta", "totalGastado", "desde", "2026-10-20", "hasta", "2026-10-01"),
                Map.of("herramienta", "totalGastado", "desde", "2026-11-01", "hasta", "2026-11-30"),
                Map.of("herramienta", "totalGastado", "desde", "1990-01-01", "hasta", "2026-10-25"),
                Map.of("herramienta", "totalGastado", "hasta", "2026-10-25"));

        ConsultaService.Respuesta r = consultas.responder("¿cuánto gasté?");

        assertThat(ultimosResultados).allSatisfy(res -> assertThat(res.error()).isTrue());
        assertThat(ultimosResultados).extracting(ClienteIa.ResultadoHerramienta::contenido)
                .containsExactly(
                        "La fecha desde \"2026-02-30\" no es una fecha real en formato AAAA-MM-DD",
                        "desde (2026-10-20) es posterior a hasta (2026-10-01)",
                        "La fecha desde (2026-11-01) es futura: hoy es 2026-10-25",
                        "La fecha desde es anterior al 2000",
                        "Falta el parámetro desde");
        verify(herramientas, never()).totalGastado(any(), any(), any());
        assertThat(r.resultados()).isEmpty();
    }

    @Test
    void unaHerramientaQueNoExisteOUnFiltroInvalidoVuelvenComoError() {
        when(herramientas.gastoPorCategoria(any(), any()))
                .thenThrow(new HerramientasConsulta.HerramientaInvalida("No existe la categoría \"x\""));
        laIa("Todavía no sé responder eso.",
                Map.of("herramienta", "borrarTodo"),
                Map.of("herramienta", "gastoPorCategoria", "desde", "2026-10-01", "hasta", "2026-10-25"));

        ConsultaService.Respuesta r = consultas.responder("borrá todo");

        assertThat(ultimosResultados).extracting(ClienteIa.ResultadoHerramienta::contenido)
                .containsExactly("No existe la herramienta borrarTodo", "No existe la categoría \"x\"");
        assertThat(r.texto()).isEqualTo("Todavía no sé responder eso.");
    }

    @Test
    void ingresosDelMesActualSiNoDiceCualYCompararDosPeriodos() {
        when(herramientas.ingresosYDisponible(YearMonth.of(2026, 10))).thenReturn(
                new HerramientasConsulta.IngresosYDisponible("2026-10", new BigDecimal("380000.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("380000.00"), new BigDecimal("24650.00"),
                        new BigDecimal("355350.00"), 6));
        laIa("Te quedan $355.350 disponibles.", Map.of("herramienta", "ingresosYDisponible"),
                Map.of("herramienta", "compararPeriodos", "desdeA", "2026-10-01", "hastaA", "2026-10-31",
                        "desdeB", "2026-09-01", "hastaB", "2026-09-30"));

        ConsultaService.Respuesta r = consultas.responder("¿Cuánto me queda disponible?");

        assertThat(r.texto()).isEqualTo("Te quedan $355.350 disponibles.");
        verify(herramientas).compararPeriodos(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void sinTextoDeLaIaQuedanLosResultados() {
        laIa("", Map.of("herramienta", "totalGastado", "desde", "2026-10-01", "hasta", "2026-10-25"));

        ConsultaService.Respuesta r = consultas.responder("¿cuánto gasté?");

        assertThat(r.texto()).isNull();
        assertThat(r.descartado()).isFalse();
        assertThat(r.resultados()).hasSize(1);
    }

    @Test
    void laTablaDeRangosLaCalculaJava() {
        String rangos = ConsultaService.rangos(HOY); // domingo 25/10/2026

        assertThat(rangos)
                .contains("hoy: 2026-10-25 a 2026-10-25")
                .contains("esta semana (lunes a hoy): 2026-10-19 a 2026-10-25")
                .contains("la semana pasada: 2026-10-12 a 2026-10-18")
                .contains("este mes (hasta hoy): 2026-10-01 a 2026-10-25")
                .contains("el mes pasado: 2026-09-01 a 2026-09-30")
                .contains("agosto 2026: 2026-08-01 a 2026-08-31")
                .contains("octubre 2025: 2025-10-01 a 2025-10-31");
    }

    @Test
    void lasSieteHerramientas() {
        assertThat(ConsultaService.definiciones()).extracting(ClienteIa.Herramienta::nombre)
                .containsExactly("totalGastado", "gastoPorCategoria", "compararPeriodos", "listarMovimientos",
                        "buscarProducto", "ingresosYDisponible", "recibosSueldo");
    }

    private static ar.gastito.recibo.AnalisisRecibos.ResumenRecibos recibosDelAnio() {
        return new ar.gastito.recibo.AnalisisRecibos.ResumenRecibos(LocalDate.of(2026, 1, 1), HOY, 2,
                List.of(), new BigDecimal("2000000.00"), new BigDecimal("400000.00"), new BigDecimal("1600000.00"),
                List.of(new ar.gastito.recibo.AnalisisRecibos.DescuentoTotal("Impuesto a las ganancias",
                        new BigDecimal("50000.00"))),
                null);
    }

    @Test
    void laHerramientaDeRecibosUsaElRangoPedido() {
        when(recibos.resumen(any(), any())).thenReturn(recibosDelAnio());
        laIa("Te descontaron $50.000 de ganancias este año.",
                Map.of("herramienta", "recibosSueldo", "desde", "2026-01-01", "hasta", "2026-10-25"));

        ConsultaService.Respuesta r = consultas.responder("¿Cuánto me descontaron de ganancias este año?");

        assertThat(r.texto()).isEqualTo("Te descontaron $50.000 de ganancias este año.");
        assertThat(r.descartado()).isFalse();
        assertThat(r.resultados()).containsExactly(recibosDelAnio());
        verify(recibos).resumen(LocalDate.of(2026, 1, 1), HOY);
    }

    @Test
    void unNumeroQueNoVienedelResumenDeRecibosDescartaElTexto() {
        when(recibos.resumen(any(), any())).thenReturn(recibosDelAnio());
        laIa("Te descontaron $70.000 de ganancias este año.",
                Map.of("herramienta", "recibosSueldo", "desde", "2026-01-01", "hasta", "2026-10-25"));

        assertThat(consultas.responder("¿Cuánto me descontaron de ganancias?").descartado()).isTrue();
    }

    @Test
    void laHerramientaDeRecibosValidaLasFechas() {
        ClienteIa.ResultadoHerramienta r = consultas.ejecutar("recibosSueldo",
                json.valueToTree(Map.of("desde", "2026-10-25", "hasta", "2026-01-01")), HOY, new ArrayList<>());

        assertThat(r.error()).isTrue();
        assertThat(r.contenido()).contains("posterior");
    }

    @Test
    void elPromptTieneTodoReemplazado() {
        laIa("Ok.");
        consultas.responder("hola?");

        verify(ia).conversar(eq("claude-haiku-5-5"), eq(1024L),
                org.mockito.ArgumentMatchers.argThat(p -> !p.contains("{{") && p.contains("Kiosco")
                        && p.contains("domingo 25 de octubre de 2026")),
                eq("hola?"), anyList(), eq(3), any(), eq(PropositoIa.CONSULTA));
    }
}
