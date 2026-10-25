package ar.gastito.ia;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import ar.gastito.config.GastitoProperties;

/** Contra una API de Anthropic falsa (WireMock): sin llamadas reales ni costo. */
class ClienteIaTest {

    static final String MODELO = "claude-haiku-5-5";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    /** Un record cualquiera para la respuesta estructurada. */
    record Lectura(String comercio, List<String> productos) {
    }

    @RegisterExtension
    static WireMockExtension anthropic = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final RegistroUsoIa registro = mock(RegistroUsoIa.class);
    private ClienteIa cliente;

    @BeforeEach
    void setUp() {
        UsoIa uso = new UsoIa(LocalDateTime.now(), MODELO, 1500, 300, PropositoIa.TICKET, BigDecimal.ZERO);
        ReflectionTestUtils.setField(uso, "id", 7L);
        when(registro.registrar(anyString(), anyLong(), anyLong(), any())).thenReturn(uso);
        cliente = new ClienteIa(new GastitoProperties(null, null,
                new GastitoProperties.Ia("clave-de-prueba", anthropic.baseUrl(), MODELO, 1024, Map.of()), null, null, null, null),
                registro);
    }

    @AfterEach
    void cerrar() {
        cliente.destroy();
    }

    @Test
    void mandaLaImagenAntesDelTextoYDevuelveElRecord() throws Exception {
        responder("end_turn", json(Map.of("comercio", "Supermercado Día", "productos", List.of("Leche", "Yerba"))));

        ClienteIa.Respuesta<Lectura> respuesta = cliente.extraerDeImagen("Leé el ticket", JPEG, "con débito", 4096,
                Lectura.class, PropositoIa.TICKET);

        assertThat(respuesta.valor()).isEqualTo(new Lectura("Supermercado Día", List.of("Leche", "Yerba")));
        assertThat(respuesta.usoIaId()).isEqualTo(7L);
        anthropic.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("clave-de-prueba"))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4096")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].type", equalTo("image")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].source.media_type", equalTo("image/jpeg")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].source.data",
                        equalTo(Base64.getEncoder().encodeToString(JPEG))))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].type", equalTo("text")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].text", equalTo("con débito"))));
    }

    @Test
    void registraElUsoConElPropositoYLosTokens() throws Exception {
        responder("end_turn", json(Map.of("comercio", "Kiosco", "productos", List.of())));

        cliente.extraerDeImagen("Leé el ticket", JPEG, "", 4096, Lectura.class, PropositoIa.TICKET);

        verify(registro).registrar(MODELO, 1500, 300, PropositoIa.TICKET);
    }

    @Test
    void unTextoUsaElTopeDeTokensDeLaConfiguracion() throws Exception {
        responder("end_turn", json(Map.of("comercio", "Kiosco", "productos", List.of())));

        cliente.extraer("Interpretá", "kiosco 1500", Lectura.class, PropositoIa.TEXTO);

        anthropic.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("1024")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].text", equalTo("kiosco 1500"))));
    }

    @Test
    void unJsonMalFormadoEsIaNoDisponibleYElUsoQuedaRegistrado() {
        responder("end_turn", "{\"comercio\": \"Supermercado Día\", \"productos\": [\"Le");

        assertThatThrownBy(() -> cliente.extraerDeImagen("Leé el ticket", JPEG, "", 4096, Lectura.class,
                PropositoIa.TICKET))
                .isInstanceOf(IaNoDisponibleException.class)
                .hasMessageContaining("no se pudo leer");
        verify(registro).registrar(MODELO, 1500, 300, PropositoIa.TICKET);
    }

    @Test
    void unaRespuestaCortadaPorElTopeEsIaNoDisponible() {
        responder("max_tokens", "{\"comercio\": \"Supermercado Día\", \"productos\": [");

        assertThatThrownBy(() -> cliente.extraerDeImagen("Leé el ticket", JPEG, "", 4096, Lectura.class,
                PropositoIa.TICKET))
                .isInstanceOf(IaNoDisponibleException.class)
                .hasMessageContaining("no devolvió una respuesta completa");
    }

    @Test
    void sinClaveNoLlama() {
        ClienteIa sinClave = new ClienteIa(new GastitoProperties(null, null,
                new GastitoProperties.Ia("", anthropic.baseUrl(), MODELO, 1024, Map.of()), null, null, null, null), registro);

        assertThatThrownBy(() -> sinClave.extraerDeImagen("x", JPEG, "", 4096, Lectura.class, PropositoIa.TICKET))
                .isInstanceOf(IaNoDisponibleException.class);
        assertThat(anthropic.getAllServeEvents()).isEmpty();
    }

    private static void responder(String stopReason, String texto) {
        Map<String, Object> mensaje = Map.of(
                "id", "msg_prueba",
                "type", "message",
                "role", "assistant",
                "model", MODELO,
                "content", List.of(Map.of("type", "text", "text", texto)),
                "stop_reason", stopReason,
                "usage", Map.of("input_tokens", 1500, "output_tokens", 300));
        try {
            anthropic.stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson(json(mensaje))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(Object valor) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(valor);
    }

    // --- Conversación con herramientas ---

    static final List<ClienteIa.Herramienta> HERRAMIENTAS = List.of(new ClienteIa.Herramienta("totalGastado",
            "Cuánto gastó", Map.of("desde", Map.of("type", "string"), "hasta", Map.of("type", "string")),
            List.of("desde", "hasta")));

    /** Una respuesta de la API que pide una herramienta. */
    private static String pideHerramienta(String id, String desde) throws JsonProcessingException {
        return json(Map.of("id", "msg_" + id, "type", "message", "role", "assistant", "model", MODELO,
                "content", List.of(Map.of("type", "tool_use", "id", id, "name", "totalGastado",
                        "input", Map.of("desde", desde, "hasta", "2026-10-25"))),
                "stop_reason", "tool_use", "usage", Map.of("input_tokens", 1000, "output_tokens", 50)));
    }

    private static String responde(String texto) throws JsonProcessingException {
        return json(Map.of("id", "msg_fin", "type", "message", "role", "assistant", "model", MODELO,
                "content", List.of(Map.of("type", "text", "text", texto)),
                "stop_reason", "end_turn", "usage", Map.of("input_tokens", 1200, "output_tokens", 40)));
    }

    /** Las respuestas de la API, una por llamada, en orden. */
    private static void enOrden(String... cuerpos) {
        for (int i = 0; i < cuerpos.length; i++) {
            anthropic.stubFor(post(urlEqualTo("/v1/messages")).inScenario("conversacion")
                    .whenScenarioStateIs(i == 0 ? com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED : "paso" + i)
                    .willSetStateTo("paso" + (i + 1))
                    .willReturn(okJson(cuerpos[i])));
        }
    }

    @Test
    void ejecutaLaHerramientaQuePideYDevuelveElTextoFinal() throws Exception {
        enOrden(pideHerramienta("tu_1", "2026-10-01"), responde("Gastaste $24.650 este mes."));
        List<String> pedidas = new java.util.ArrayList<>();

        ClienteIa.Conversacion c = cliente.conversar(MODELO, 1024, "Respondé", "¿Cuánto gasté?", HERRAMIENTAS, 3,
                (nombre, parametros) -> {
                    pedidas.add(nombre + " " + parametros.get("desde").asText());
                    return new ClienteIa.ResultadoHerramienta("{\"total\":24650.00}", false);
                }, PropositoIa.CONSULTA);

        assertThat(c.texto()).isEqualTo("Gastaste $24.650 este mes.");
        assertThat(c.llamadas()).isEqualTo(1);
        assertThat(c.topeAlcanzado()).isFalse();
        assertThat(pedidas).containsExactly("totalGastado 2026-10-01");
        // La segunda llamada lleva el pedido de la IA y el resultado de Java.
        anthropic.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].type", equalTo("tool_use")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].type", equalTo("tool_result")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].tool_use_id", equalTo("tu_1")))
                .withRequestBody(matchingJsonPath("$.tools[0].name", equalTo("totalGastado"))));
        // Un solo registro en uso_ia, con las dos llamadas sumadas.
        verify(registro).registrar(MODELO, 2200, 90, PropositoIa.CONSULTA);
    }

    @Test
    void conElTopeDeTresLlamadasLaUltimaNoOfreceHerramientas() throws Exception {
        enOrden(pideHerramienta("tu_1", "2026-10-01"), pideHerramienta("tu_2", "2026-09-01"),
                pideHerramienta("tu_3", "2026-08-01"), responde("Con lo que tengo: $24.650."));
        List<String> ejecutadas = new java.util.ArrayList<>();

        ClienteIa.Conversacion c = cliente.conversar(MODELO, 1024, "Respondé", "¿Cuánto gasté?", HERRAMIENTAS, 3,
                (nombre, parametros) -> {
                    ejecutadas.add(parametros.get("desde").asText());
                    return new ClienteIa.ResultadoHerramienta("{\"total\":24650.00}", false);
                }, PropositoIa.CONSULTA);

        assertThat(ejecutadas).containsExactly("2026-10-01", "2026-09-01", "2026-08-01");
        assertThat(c.llamadas()).isEqualTo(3);
        assertThat(c.topeAlcanzado()).isTrue();
        assertThat(c.texto()).isEqualTo("Con lo que tengo: $24.650.");
        // Después de la tercera, se pide la respuesta sin herramientas.
        anthropic.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.type", equalTo("none"))));
        verify(registro).registrar(MODELO, 4200, 190, PropositoIa.CONSULTA);
    }

    @Test
    void unErrorDeLaHerramientaVuelveALaIaParaQueSeCorrija() throws Exception {
        enOrden(pideHerramienta("tu_1", "2026-13-45"), pideHerramienta("tu_2", "2026-10-01"),
                responde("Gastaste $24.650."));

        ClienteIa.Conversacion c = cliente.conversar(MODELO, 1024, "Respondé", "¿Cuánto gasté?", HERRAMIENTAS, 3,
                (nombre, parametros) -> parametros.get("desde").asText().startsWith("2026-13")
                        ? new ClienteIa.ResultadoHerramienta("La fecha desde no es una fecha real", true)
                        : new ClienteIa.ResultadoHerramienta("{\"total\":24650.00}", false),
                PropositoIa.CONSULTA);

        assertThat(c.texto()).isEqualTo("Gastaste $24.650.");
        anthropic.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].is_error", equalTo("true"))));
    }
}
