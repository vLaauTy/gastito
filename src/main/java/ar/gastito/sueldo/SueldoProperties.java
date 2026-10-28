package ar.gastito.sueldo;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Calculadora de sueldo y control de recibos.
 *
 * @param toleranciaCobroPorcentaje desde qué diferencia (en %) entre lo calculado y lo cobrado se marca con ⚠️
 * @param toleranciaConcepto        diferencia en pesos que se perdona al comparar un concepto del recibo con el cálculo
 * @param toleranciaPorcentaje      diferencia en puntos que se perdona al comparar un porcentaje de descuento
 */
@ConfigurationProperties(prefix = "gastito.sueldo")
public record SueldoProperties(
        @DefaultValue("1") BigDecimal toleranciaCobroPorcentaje,
        @DefaultValue("1") BigDecimal toleranciaConcepto,
        @DefaultValue("0.05") BigDecimal toleranciaPorcentaje) {
}
