package ar.gastito.viaje;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param diaSueldoPorDefecto el día del mes en que se cobra el sueldo, si todavía no hay ninguno cargado (para
 *                            "cuánto ahorrar")
 * @param horaAvisos          antes de esta hora no se manda ningún aviso del modo viaje
 */
@ConfigurationProperties(prefix = "gastito.viajes")
public record ViajesProperties(
        @DefaultValue("5") int diaSueldoPorDefecto,
        @DefaultValue("9") int horaAvisos) {
}
