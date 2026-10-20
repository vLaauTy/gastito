package ar.gastito.audio;

import java.net.http.HttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import ar.gastito.audio.TranscripcionNoDisponibleException.Motivo;
import ar.gastito.config.GastitoProperties;

/**
 * Transcribe audios con un servidor de Whisper compatible con la API de OpenAI
 * ({@code POST /v1/audio/transcriptions}). Whisper corre local: no se registra en uso_ia.
 * <p>
 * Ni la URL ni la clave llegan a los logs: los errores de RestClient incluyen la URL, así que solo se loguea
 * el tipo de error.
 */
@Component
public class ClienteTranscripcion {

    private static final Logger log = LoggerFactory.getLogger(ClienteTranscripcion.class);
    static final String RUTA = "/v1/audio/transcriptions";

    private final GastitoProperties.Audio config;
    private final RestClient http;

    public ClienteTranscripcion(GastitoProperties properties) {
        this.config = properties.audio();
        // HTTP/1.1: con el upgrade a HTTP/2 por defecto del JDK, servidores que no lo soportan cortan la conexión.
        HttpClient cliente = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.timeout())
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(config.timeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(config.url()).requestFactory(fabrica);
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        } else {
            log.warn("WHISPER_API_KEY no está configurada: el servidor de Whisper va a rechazar los audios");
        }
        this.http = builder.build();
    }

    record RespuestaWhisper(String text) {
    }

    /**
     * @param nombreArchivo con extensión (por ejemplo voz.oga): Whisper la usa para reconocer el formato
     * @param prompt        palabras que Whisper tiene que escribir bien (categorías, cuentas…)
     * @return el texto transcripto, sin espacios en los bordes; vacío si no se escuchó nada
     */
    public String transcribir(byte[] audio, String nombreArchivo, String prompt) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return nombreArchivo;
            }
        });
        partes.add("model", config.modelo());
        partes.add("language", config.idioma());
        partes.add("prompt", prompt);
        partes.add("response_format", "json");

        RespuestaWhisper respuesta;
        try {
            respuesta = http.post()
                    .uri(RUTA)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(partes)
                    .retrieve()
                    .body(RespuestaWhisper.class);
        } catch (ResourceAccessException e) {
            String causa = e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName();
            log.warn("El servidor de Whisper no respondió ({})", causa);
            throw new TranscripcionNoDisponibleException(Motivo.SIN_RESPUESTA, "El servidor de Whisper no respondió");
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("El servidor de Whisper respondió HTTP {}", status);
            if (status == 401 || status == 403) {
                throw new TranscripcionNoDisponibleException(Motivo.CLAVE_RECHAZADA,
                        "El servidor de Whisper rechazó la clave");
            }
            throw new TranscripcionNoDisponibleException(Motivo.ERROR_DEL_SERVIDOR,
                    "El servidor de Whisper respondió con un error (HTTP " + status + ")");
        } catch (RestClientException e) {
            log.warn("No se pudo leer la respuesta de Whisper ({})", e.getClass().getSimpleName());
            throw new TranscripcionNoDisponibleException(Motivo.ERROR_DEL_SERVIDOR,
                    "No se pudo leer la respuesta del servidor de Whisper");
        }
        return respuesta == null || respuesta.text() == null ? "" : respuesta.text().strip();
    }
}
