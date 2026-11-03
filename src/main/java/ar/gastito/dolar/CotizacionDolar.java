package ar.gastito.dolar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La cotización de referencia del dólar tarjeta que cargó el usuario ("/dolar 1500"). Vale la última; las
 * anteriores quedan como historial. Sirve solo para estimar: lo real sale del resumen de la tarjeta.
 */
@Entity
@Table(name = "cotizacion_dolar")
public class CotizacionDolar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Pesos por dólar. */
    private BigDecimal valor;

    private LocalDateTime cargadaEn;

    protected CotizacionDolar() {
    }

    public CotizacionDolar(BigDecimal valor, LocalDateTime cargadaEn) {
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("La cotización tiene que ser mayor a cero");
        }
        this.valor = valor.setScale(2, RoundingMode.HALF_UP);
        this.cargadaEn = cargadaEn;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public LocalDateTime getCargadaEn() {
        return cargadaEn;
    }
}
