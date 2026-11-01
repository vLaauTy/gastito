package ar.gastito.tarjeta;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param diasParaPreguntarFechas   cuántos días después del cierre se empieza a preguntar por las fechas del
 *                                  próximo resumen, si siguen estimadas
 * @param diasAlPosponerFechas      "Todavía no": en cuántos días se vuelve a preguntar
 * @param diasAvisoVencimiento      cuántos días antes del vencimiento se avisa cuánto se va a debitar
 * @param horaAvisos                antes de esta hora no se manda ningún aviso de la tarjeta
 * @param toleranciaDiferencia      hasta cuánto de diferencia entre el total del resumen y lo cargado se toma como igual
 * @param resumenesSiguientes       cuántos resúmenes después del próximo se muestran con las cuotas comprometidas
 */
@ConfigurationProperties(prefix = "gastito.tarjeta")
public record TarjetaProperties(
        @DefaultValue("2") int diasParaPreguntarFechas,
        @DefaultValue("3") int diasAlPosponerFechas,
        @DefaultValue("3") int diasAvisoVencimiento,
        @DefaultValue("9") int horaAvisos,
        @DefaultValue("1") BigDecimal toleranciaDiferencia,
        @DefaultValue("6") int resumenesSiguientes) {
}
