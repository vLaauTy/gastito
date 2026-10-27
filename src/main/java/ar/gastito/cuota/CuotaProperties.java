package ar.gastito.cuota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param primeraCuota si la primera cuota es la del mes de la compra, o la tarjeta cobra al mes siguiente */
@ConfigurationProperties(prefix = "gastito.cuotas")
public record CuotaProperties(@DefaultValue("MISMO_MES") PrimeraCuota primeraCuota) {

    public enum PrimeraCuota {
        MISMO_MES,
        MES_SIGUIENTE
    }
}
