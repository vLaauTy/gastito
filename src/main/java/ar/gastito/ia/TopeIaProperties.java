package ar.gastito.ia;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * El tope mensual de gasto en IA. No bloquea nada: solo hace que el bot avise al llegar al 80 % y al 100 %.
 *
 * @param topeMensualUsd en dólares; 0 o menos apaga los avisos
 */
@ConfigurationProperties(prefix = "gastito.ia")
public record TopeIaProperties(@DefaultValue("2") BigDecimal topeMensualUsd) {
}
