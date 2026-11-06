package ar.gastito.inversion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param diasRecordatorio cada cuántos días se pregunta cuánto vale una cuenta de inversión nueva (0: no se pregunta;
 *                         después se cambia por cuenta, en el dashboard o con "No preguntar más")
 * @param horaRecordatorio antes de esta hora no se manda el recordatorio
 */
@ConfigurationProperties(prefix = "gastito.inversiones")
public record InversionesProperties(
        @DefaultValue("7") int diasRecordatorio,
        @DefaultValue("10") int horaRecordatorio) {
}
