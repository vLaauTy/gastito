package ar.gastito.sueldo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Calculadora de sueldo y control de recibos.
 *
 * @param toleranciaCobroPorcentaje desde qué diferencia (en %) entre lo calculado y lo cobrado se marca con ⚠️
 * @param toleranciaConcepto        diferencia en pesos que se perdona al comparar un concepto del recibo con el cálculo
 * @param toleranciaPorcentaje      diferencia en puntos que se perdona al comparar un porcentaje de descuento
 * @param diaLimitePeriodoAnterior  hasta qué día del mes un sueldo cobrado es del mes anterior (se cobra alrededor del 5)
 * @param maxTokensEscala           tope de la respuesta de la IA al leer la foto de la hoja de la escala
 * @param descuentosPorDefecto      mis porcentajes de descuento, en el orden de los conceptos de descuento activos
 *                                  (jubilación, obra social, ley 19.032, sindicato): los toma una escala que no tiene
 *                                  una anterior de donde sacarlos
 * @param porcentajeControlHoja     el descuento fijo con el que la hoja del sindicato calcula "Valores netos" (no
 *                                  son mis descuentos: solo sirve para controlar que la hoja se leyó bien)
 */
@ConfigurationProperties(prefix = "gastito.sueldo")
public record SueldoProperties(
        @DefaultValue("1") BigDecimal toleranciaCobroPorcentaje,
        @DefaultValue("1") BigDecimal toleranciaConcepto,
        @DefaultValue("0.05") BigDecimal toleranciaPorcentaje,
        @DefaultValue("15") int diaLimitePeriodoAnterior,
        @DefaultValue("3000") long maxTokensEscala,
        @DefaultValue({"11", "3", "3", "2.5"}) List<BigDecimal> descuentosPorDefecto,
        @DefaultValue("20") BigDecimal porcentajeControlHoja) {
}
