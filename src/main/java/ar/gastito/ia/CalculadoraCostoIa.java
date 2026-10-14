package ar.gastito.ia;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;

/** Costo en USD de una llamada, con los precios por millón de tokens de application.yml. */
@Component
public class CalculadoraCostoIa {

    private static final Logger log = LoggerFactory.getLogger(CalculadoraCostoIa.class);
    private static final BigDecimal UN_MILLON = BigDecimal.valueOf(1_000_000);
    /** Mismo número de decimales que la columna uso_ia.costo_usd. */
    static final int ESCALA = 8;

    private final GastitoProperties properties;

    public CalculadoraCostoIa(GastitoProperties properties) {
        this.properties = properties;
    }

    public BigDecimal costo(String modelo, long tokensEntrada, long tokensSalida) {
        GastitoProperties.Precio precio = properties.ia().precios().get(modelo);
        if (precio == null) {
            log.warn("No hay precio configurado para el modelo {}: se registra con costo 0", modelo);
            return BigDecimal.ZERO.setScale(ESCALA);
        }
        BigDecimal entrada = precio.entrada().multiply(BigDecimal.valueOf(tokensEntrada));
        BigDecimal salida = precio.salida().multiply(BigDecimal.valueOf(tokensSalida));
        return entrada.add(salida).divide(UN_MILLON, ESCALA, RoundingMode.HALF_UP);
    }
}
