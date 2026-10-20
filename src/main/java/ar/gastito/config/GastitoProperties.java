package ar.gastito.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastito")
public record GastitoProperties(ZoneId zonaHoraria, Telegram telegram, Ia ia, Audio audio) {

    public record Telegram(String token, String username, long allowedUserId) {
    }

    /**
     * @param precios precios en USD por millón de tokens, por modelo
     */
    public record Ia(String apiKey, String modelo, long maxTokens, Map<String, Precio> precios) {
    }

    public record Precio(BigDecimal entrada, BigDecimal salida) {
    }

    /**
     * Transcripción de audios con un servidor de Whisper compatible con la API de OpenAI.
     *
     * @param url            sin la ruta, por ejemplo http://localhost:9000
     * @param apiKey         solo desde el .env (WHISPER_API_KEY)
     * @param timeout        cuánto esperar la transcripción
     * @param duracionMaxima audios más largos no se procesan
     */
    public record Audio(String url, String apiKey, String modelo, String idioma, Duration timeout,
            Duration duracionMaxima) {
    }
}
