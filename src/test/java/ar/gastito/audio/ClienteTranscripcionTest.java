package ar.gastito.audio;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import ar.gastito.audio.TranscripcionNoDisponibleException.Motivo;
import ar.gastito.config.GastitoProperties;

/** Contra un servidor de Whisper falso (WireMock): no hace falta Docker ni el servidor real. */
@ExtendWith(OutputCaptureExtension.class)
class ClienteTranscripcionTest {

    static final String CLAVE = "clave-secreta-de-prueba";
    static final byte[] AUDIO = { 'O', 'g', 'g', 'S', 1, 2, 3 };

    @RegisterExtension
    static WireMockExtension whisper = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static ClienteTranscripcion cliente(String url, Duration timeout) {
        return new ClienteTranscripcion(new GastitoProperties(null, null, null,
                new GastitoProperties.Audio(url, CLAVE, "whisper-1", "es", timeout, Duration.ofSeconds(60)), null));
    }

    private ClienteTranscripcion cliente() {
        return cliente(whisper.baseUrl(), Duration.ofSeconds(5));
    }

    @Test
    void mandaElAudioConLosCamposYLaClave() {
        whisper.stubFor(post(ClienteTranscripcion.RUTA).willReturn(okJson("{\"text\": \"kiosco 1500\"}")));

        cliente().transcribir(AUDIO, "voz.oga", "Kiosco, Supermercado, Galicia");

        whisper.verify(postRequestedFor(urlEqualTo(ClienteTranscripcion.RUTA))
                .withHeader("Authorization", equalTo("Bearer " + CLAVE))
                .withRequestBodyPart(aMultipart("file").withBody(binaryEqualTo(AUDIO)).build())
                .withRequestBodyPart(aMultipart("model").withBody(equalTo("whisper-1")).build())
                .withRequestBodyPart(aMultipart("language").withBody(equalTo("es")).build())
                .withRequestBodyPart(aMultipart("prompt").withBody(equalTo("Kiosco, Supermercado, Galicia")).build())
                .withRequestBodyPart(aMultipart("response_format").withBody(equalTo("json")).build()));
        assertThat(whisper.getAllServeEvents().get(0).getRequest().getPart("file").getHeader("Content-Disposition")
                .firstValue()).contains("filename=\"voz.oga\"");
    }

    @Test
    void devuelveElTextoSinEspaciosEnLosBordes() {
        whisper.stubFor(post(ClienteTranscripcion.RUTA)
                .willReturn(okJson("{\"text\": \" Gasto 3700 en pan de pancho y papas, efectivo. \"}")));

        assertThat(cliente().transcribir(AUDIO, "voz.oga", ""))
                .isEqualTo("Gasto 3700 en pan de pancho y papas, efectivo.");
    }

    @Test
    void sinTextoDevuelveVacio() {
        whisper.stubFor(post(ClienteTranscripcion.RUTA).willReturn(okJson("{\"text\": \"\"}")));

        assertThat(cliente().transcribir(AUDIO, "voz.oga", "")).isEmpty();
    }

    @Test
    void siTardaMasQueElTimeoutEsSinRespuesta() {
        whisper.stubFor(post(ClienteTranscripcion.RUTA)
                .willReturn(okJson("{\"text\": \"tarde\"}").withFixedDelay(2_000)));
        ClienteTranscripcion apurado = cliente(whisper.baseUrl(), Duration.ofMillis(300));

        assertThatThrownBy(() -> apurado.transcribir(AUDIO, "voz.oga", ""))
                .isInstanceOfSatisfying(TranscripcionNoDisponibleException.class,
                        e -> assertThat(e.getMotivo()).isEqualTo(Motivo.SIN_RESPUESTA));
    }

    @Test
    void servidorApagadoEsSinRespuestaYNoLogueaUrlNiClave(CapturedOutput salida) {
        String apagado = "http://127.0.0.1:1";

        assertThatThrownBy(() -> cliente(apagado, Duration.ofSeconds(2)).transcribir(AUDIO, "voz.oga", ""))
                .isInstanceOfSatisfying(TranscripcionNoDisponibleException.class, e -> {
                    assertThat(e.getMotivo()).isEqualTo(Motivo.SIN_RESPUESTA);
                    assertThat(e.getMessage()).doesNotContain(apagado, CLAVE);
                });
        assertThat(salida.getAll()).contains("Whisper no respondió").doesNotContain(apagado, CLAVE);
    }

    @Test
    void claveRechazada() {
        whisper.stubFor(post(ClienteTranscripcion.RUTA).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> cliente().transcribir(AUDIO, "voz.oga", ""))
                .isInstanceOfSatisfying(TranscripcionNoDisponibleException.class,
                        e -> assertThat(e.getMotivo()).isEqualTo(Motivo.CLAVE_RECHAZADA));
    }

    @Test
    void errorDelServidorNoLogueaUrlNiClave(CapturedOutput salida) {
        whisper.stubFor(post(ClienteTranscripcion.RUTA).willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> cliente().transcribir(AUDIO, "voz.oga", ""))
                .isInstanceOfSatisfying(TranscripcionNoDisponibleException.class,
                        e -> assertThat(e.getMotivo()).isEqualTo(Motivo.ERROR_DEL_SERVIDOR));
        assertThat(salida.getAll()).contains("HTTP 500").doesNotContain(whisper.baseUrl(), CLAVE);
    }

    @Test
    void respuestaQueNoEsJsonEsErrorDelServidor() {
        whisper.stubFor(post(ClienteTranscripcion.RUTA)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{no")));

        assertThatThrownBy(() -> cliente().transcribir(AUDIO, "voz.oga", ""))
                .isInstanceOfSatisfying(TranscripcionNoDisponibleException.class,
                        e -> assertThat(e.getMotivo()).isEqualTo(Motivo.ERROR_DEL_SERVIDOR));
    }
}
