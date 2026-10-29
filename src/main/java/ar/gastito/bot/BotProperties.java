package ar.gastito.bot;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param guardadoAutomatico     si un gasto claro se guarda solo (con ✏️ Editar y ↩️ Deshacer); en false, todo se
 *                               confirma con el botón Guardar
 * @param montoMaximoAutomatico  un gasto de más que esto se confirma igual, aunque esté claro
 */
@ConfigurationProperties(prefix = "gastito.bot")
public record BotProperties(@DefaultValue("true") boolean guardadoAutomatico,
        @DefaultValue("100000") BigDecimal montoMaximoAutomatico) {
}
