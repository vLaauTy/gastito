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
                new GastitoProperties.Ia("clave-de-prueba", anthropic.baseUrl(), MODELO, 1024, Map.of()), null, null),
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
                new GastitoProperties.Ia("", anthropic.baseUrl(), MODELO, 1024, Map.of()), null, null), registro);

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
}
