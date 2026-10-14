package ar.gastito.ia;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigDisabled;

import ar.gastito.config.GastitoProperties;

/**
 * Llamadas a Claude con respuesta estructurada (JSON validado contra un record).
 * Cada llamada que llega a responder queda registrada en uso_ia con sus tokens y su costo.
 */
@Component
public class ClienteIa implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ClienteIa.class);

    private final GastitoProperties.Ia config;
    private final RegistroUsoIa registro;
    private final AnthropicClient client;

    public ClienteIa(GastitoProperties properties, RegistroUsoIa registro) {
        this.config = properties.ia();
        this.registro = registro;
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            log.warn("ANTHROPIC_API_KEY no está configurada: solo va a funcionar el parser simple");
            this.client = null;
        } else {
            this.client = AnthropicOkHttpClient.builder().apiKey(config.apiKey()).build();
        }
    }

    /** Resultado de una extracción y el id del registro en uso_ia. */
    public record Respuesta<T>(T valor, Long usoIaId) {
    }

    public <T> Respuesta<T> extraer(String instrucciones, String mensaje, Class<T> tipo, PropositoIa proposito) {
        if (client == null) {
            throw new IaNoDisponibleException("Falta configurar ANTHROPIC_API_KEY", null);
        }
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(config.modelo())
                .maxTokens(config.maxTokens())
                // Extracción simple: sin razonamiento extendido, que se cobra como salida.
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(instrucciones)
                .addUserMessage(mensaje)
                .outputConfig(tipo)
                .build();

        StructuredMessage<T> respuesta;
        try {
            respuesta = client.messages().create(params);
        } catch (AnthropicException e) {
            // El mensaje de error del SDK no incluye la API key.
            log.warn("Falló la llamada a la IA: {}", e.getMessage());
            throw new IaNoDisponibleException("No pude consultar a la IA", e);
        }

        UsoIa uso = registro.registrar(config.modelo(), respuesta.usage().inputTokens(),
                respuesta.usage().outputTokens(), proposito);

        Optional<StopReason> stop = respuesta.stopReason();
        if (stop.isPresent() && !StopReason.END_TURN.equals(stop.get())) {
            log.warn("La IA terminó con stop_reason {}", stop.get());
            throw new IaNoDisponibleException("La IA no devolvió una respuesta completa", null);
        }
        T valor = respuesta.content().stream()
                .flatMap(bloque -> bloque.text().stream())
                .map(texto -> texto.text())
                .findFirst()
                .orElseThrow(() -> new IaNoDisponibleException("La IA respondió sin contenido", null));
        return new Respuesta<>(valor, uso.getId());
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
        }
    }
}
