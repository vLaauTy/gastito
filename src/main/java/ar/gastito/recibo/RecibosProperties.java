package ar.gastito.recibo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Lectura de recibos de sueldo. Los archivos van en la carpeta de datos de los tickets (gastito.ticket.carpeta-datos).
 *
 * @param maxTokens tope de la respuesta de la IA (un recibo trae unos 15 conceptos)
 */
@ConfigurationProperties(prefix = "gastito.recibos")
public record RecibosProperties(@DefaultValue("3000") long maxTokens) {
}
