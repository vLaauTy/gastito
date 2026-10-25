package ar.gastito.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "gastito")
public record GastitoProperties(ZoneId zonaHoraria, Telegram telegram, Ia ia, Audio audio, Ticket ticket,
        Resumenes resumenes, Consultas consultas) {

    public record Telegram(String token, String username, long allowedUserId) {
    }

    /**
     * @param url       la API de Anthropic; en los tests, un servidor falso
     * @param maxTokens tope de la respuesta al interpretar un texto
     * @param precios   precios en USD por millón de tokens, por modelo
     */
    public record Ia(String apiKey, String url, String modelo, long maxTokens, Map<String, Precio> precios) {
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

    /**
     * Lectura de fotos de tickets.
     *
     * @param carpetaDatos  las fotos van en carpetaDatos/tickets/AAAA/MM/<id>.jpg
     * @param ladoMaximo    la foto se achica a este lado más largo, en píxeles, antes de mandarla a la IA
     * @param tamanioMaximo imágenes más pesadas no se procesan
     * @param maxTokens     tope de la respuesta de la IA (un ticket largo tiene muchos productos)
     */
    public record Ticket(String carpetaDatos, int ladoMaximo, DataSize tamanioMaximo, long maxTokens) {
    }

    /**
     * Resúmenes automáticos (en la zona horaria de gastito.zona-horaria).
     *
     * @param horaSemanal       el domingo, a esta hora, sale el resumen de la semana
     * @param horaMensual       el día 1, a esta hora, sale el resumen del mes que terminó
     * @param modeloSemanal     el modelo que redacta el semanal
     * @param modeloMensual     el que redacta el mensual (por si conviene uno más potente)
     * @param umbralHormiga     compras por debajo de este monto, en las categorías hormiga, son gastos hormiga
     * @param categoriasHormiga nombres de las categorías donde se cuentan los gastos hormiga
     */
    public record Resumenes(LocalTime horaSemanal, LocalTime horaMensual, String modeloSemanal, String modeloMensual,
            long maxTokens, BigDecimal umbralHormiga, List<String> categoriasHormiga) {
    }

    /**
     * Preguntas en lenguaje natural, respondidas con herramientas (tool use).
     *
     * @param maxLlamadas tope de llamadas a herramientas por pregunta
     */
    public record Consultas(String modelo, long maxTokens, int maxLlamadas) {
    }
}
