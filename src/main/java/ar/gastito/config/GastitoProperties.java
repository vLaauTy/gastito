package ar.gastito.config;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastito")
public record GastitoProperties(ZoneId zonaHoraria, Telegram telegram, Ia ia) {

    public record Telegram(String token, String username, long allowedUserId) {
    }

    /**
     * @param precios precios en USD por millón de tokens, por modelo
     */
    public record Ia(String apiKey, String modelo, long maxTokens, Map<String, Precio> precios) {
    }

    public record Precio(BigDecimal entrada, BigDecimal salida) {
    }
}
