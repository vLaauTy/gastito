package ar.gastito.movimiento;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param ventana cuánto antes o después de un gasto se busca otro igual (por defecto, 3 horas) */
@ConfigurationProperties(prefix = "gastito.repetidos")
public record RepetidosProperties(@DefaultValue("3h") Duration ventana) {
}
