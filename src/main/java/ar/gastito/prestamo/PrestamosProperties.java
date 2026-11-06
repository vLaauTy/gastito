package ar.gastito.prestamo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param diasDeGracia cuántos días después de la fecha de una cuota se espera antes de avisar que está atrasada
 * @param horaAviso    antes de esta hora no se avisa
 */
@ConfigurationProperties(prefix = "gastito.prestamos")
public record PrestamosProperties(
        @DefaultValue("3") int diasDeGracia,
        @DefaultValue("10") int horaAviso) {
}
