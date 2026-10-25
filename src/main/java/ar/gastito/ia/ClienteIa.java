package ar.gastito.ia;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import com.fasterxml.jackson.databind.JsonNode;

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
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(config.apiKey());
            if (config.url() != null && !config.url().isBlank()) {
                builder.baseUrl(config.url());
            }
            this.client = builder.build();
        }
    }

    /** Resultado de una extracción y el id del registro en uso_ia. */
    public record Respuesta<T>(T valor, Long usoIaId) {
    }

    /** Un mensaje de texto (o la transcripción de un audio). */
    public <T> Respuesta<T> extraer(String instrucciones, String mensaje, Class<T> tipo, PropositoIa proposito) {
        return llamar(config.modelo(), instrucciones, List.of(texto(mensaje)), config.maxTokens(), tipo, proposito);
    }

    /**
     * Un texto con otro modelo y otro tope que los de la configuración (por ejemplo, para redactar un resumen).
     */
    public <T> Respuesta<T> extraer(String modelo, long maxTokens, String instrucciones, String mensaje,
            Class<T> tipo, PropositoIa proposito) {
        return llamar(modelo, instrucciones, List.of(texto(mensaje)), maxTokens, tipo, proposito);
    }

    /**
     * Una imagen JPEG y, después, un texto: la documentación recomienda la imagen antes que el texto.
     *
     * @param maxTokens tope de la respuesta, que para una imagen con mucho detalle (un ticket) es mayor
     */
    public <T> Respuesta<T> extraerDeImagen(String instrucciones, byte[] jpeg, String mensaje, long maxTokens,
            Class<T> tipo, PropositoIa proposito) {
        ContentBlockParam imagen = ContentBlockParam.ofImage(Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                .data(Base64.getEncoder().encodeToString(jpeg))
                .build());
        return llamar(config.modelo(), instrucciones, List.of(imagen, texto(mensaje)), maxTokens, tipo, proposito);
    }

    private <T> Respuesta<T> llamar(String modelo, String instrucciones, List<ContentBlockParam> contenido,
            long maxTokens, Class<T> tipo, PropositoIa proposito) {
        if (client == null) {
            throw new IaNoDisponibleException("Falta configurar ANTHROPIC_API_KEY", null);
        }
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(modelo)
                .maxTokens(maxTokens)
                // Extracción simple: sin razonamiento extendido, que se cobra como salida.
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(instrucciones)
                .addUserMessageOfBlockParams(contenido)
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

        UsoIa uso = registro.registrar(modelo, respuesta.usage().inputTokens(),
                respuesta.usage().outputTokens(), proposito);

        Optional<StopReason> stop = respuesta.stopReason();
        if (stop.isPresent() && !StopReason.END_TURN.equals(stop.get())) {
            log.warn("La IA terminó con stop_reason {}", stop.get());
            throw new IaNoDisponibleException("La IA no devolvió una respuesta completa", null);
        }
        T valor;
        try {
            // El SDK convierte el JSON en el record recién acá.
            valor = respuesta.content().stream()
                    .flatMap(bloque -> bloque.text().stream())
                    .map(texto -> texto.text())
                    .findFirst()
                    .orElseThrow(() -> new IaNoDisponibleException("La IA respondió sin contenido", null));
        } catch (AnthropicException e) {
            log.warn("La IA devolvió un JSON que no se pudo leer ({})", e.getClass().getSimpleName());
            throw new IaNoDisponibleException("La IA devolvió una respuesta que no se pudo leer", e);
        }
        return new Respuesta<>(valor, uso.getId());
    }

    // --- Conversación con herramientas (tool use) ---

    /**
     * Una herramienta que la IA puede pedir. Las implementa Java: la IA solo elige cuál y con qué parámetros.
     *
     * @param propiedades el JSON schema de cada parámetro ({"type": "string", "description": …})
     */
    public record Herramienta(String nombre, String descripcion, Map<String, Map<String, Object>> propiedades,
            List<String> requeridas) {
    }

    /** El resultado de ejecutar una herramienta, en JSON. Con error, el texto le explica a la IA qué corregir. */
    public record ResultadoHerramienta(String contenido, boolean error) {
    }

    @FunctionalInterface
    public interface EjecutorHerramientas {
        ResultadoHerramienta ejecutar(String nombre, JsonNode parametros);
    }

    /**
     * @param texto         la respuesta final de la IA
     * @param llamadas      cuántas veces pidió una herramienta
     * @param topeAlcanzado si pidió más herramientas que el tope (las de más no se ejecutaron)
     * @param usoIaId       un solo registro en uso_ia con todas las llamadas a la API sumadas
     */
    public record Conversacion(String texto, int llamadas, boolean topeAlcanzado, Long usoIaId) {
    }

    /**
     * Le pasa la pregunta a la IA con las herramientas y ejecuta las que pide (como mucho {@code maxLlamadas}),
     * hasta que responde con texto. Al llegar al tope, la siguiente llamada no ofrece herramientas: la IA tiene que
     * contestar con lo que tiene. Todas las llamadas a la API se registran juntas en uso_ia.
     *
     * @throws IaNoDisponibleException si no se pudo usar la IA (lo que ya se gastó queda registrado)
     */
    public Conversacion conversar(String modelo, long maxTokens, String instrucciones, String pregunta,
            List<Herramienta> herramientas, int maxLlamadas, EjecutorHerramientas ejecutor, PropositoIa proposito) {
        if (client == null) {
            throw new IaNoDisponibleException("Falta configurar ANTHROPIC_API_KEY", null);
        }
        List<MessageParam> mensajes = new ArrayList<>();
        mensajes.add(MessageParam.builder().role(MessageParam.Role.USER).content(pregunta).build());
        long entrada = 0;
        long salida = 0;
        int llamadas = 0;
        boolean tope = false;
        try {
            // Una vuelta por cada llamada a herramientas, más la respuesta final.
            for (int vuelta = 0; vuelta <= maxLlamadas + 1; vuelta++) {
                boolean sinHerramientas = llamadas >= maxLlamadas;
                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(modelo)
                        .maxTokens(maxTokens)
                        .thinking(ThinkingConfigDisabled.builder().build())
                        .system(instrucciones)
                        .messages(mensajes);
                herramientas.forEach(h -> params.addTool(tool(h)));
                if (sinHerramientas) {
                    params.toolChoice(ToolChoiceNone.builder().build());
                }
                Message respuesta;
                try {
                    respuesta = client.messages().create(params.build());
                } catch (AnthropicException e) {
                    log.warn("Falló la llamada a la IA: {}", e.getMessage());
                    throw new IaNoDisponibleException("No pude consultar a la IA", e);
                }
                entrada += respuesta.usage().inputTokens();
                salida += respuesta.usage().outputTokens();

                List<ToolUseBlock> pedidos = respuesta.content().stream()
                        .flatMap(b -> b.toolUse().stream()).toList();
                if (pedidos.isEmpty() || sinHerramientas) {
                    if (respuesta.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
                        throw new IaNoDisponibleException("La IA no devolvió una respuesta completa", null);
                    }
                    String texto = respuesta.content().stream().flatMap(b -> b.text().stream())
                            .map(t -> t.text()).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b).strip();
                    return new Conversacion(texto, llamadas, tope || sinHerramientas,
                            registrar(modelo, entrada, salida, proposito));
                }

                mensajes.add(respuesta.toParam());
                List<ContentBlockParam> resultados = new ArrayList<>();
                for (ToolUseBlock pedido : pedidos) {
                    ResultadoHerramienta r;
                    if (llamadas >= maxLlamadas) {
                        tope = true;
                        r = new ResultadoHerramienta("Se alcanzó el tope de " + maxLlamadas
                                + " consultas: respondé con lo que ya tenés.", true);
                    } else {
                        llamadas++;
                        r = ejecutor.ejecutar(pedido.name(), pedido._input().convert(JsonNode.class));
                    }
                    resultados.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(pedido.id()).content(r.contenido()).isError(r.error()).build()));
                }
                mensajes.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(resultados)
                        .build());
            }
            throw new IaNoDisponibleException("La IA no terminó de responder", null);
        } catch (IaNoDisponibleException e) {
            if (entrada + salida > 0) {
                registrar(modelo, entrada, salida, proposito);
            }
            throw e;
        }
    }

    private Long registrar(String modelo, long entrada, long salida, PropositoIa proposito) {
        return registro.registrar(modelo, entrada, salida, proposito).getId();
    }

    private static Tool tool(Herramienta h) {
        Tool.InputSchema.Properties.Builder propiedades = Tool.InputSchema.Properties.builder();
        h.propiedades().forEach((nombre, schema) -> propiedades.putAdditionalProperty(nombre, JsonValue.from(schema)));
        return Tool.builder()
                .name(h.nombre())
                .description(h.descripcion())
                .inputSchema(Tool.InputSchema.builder().properties(propiedades.build()).required(h.requeridas()).build())
                .build();
    }

    private static ContentBlockParam texto(String texto) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(texto).build());
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
        }
    }
}
