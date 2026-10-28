package ar.gastito.recibo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A qué concepto del sueldo corresponde un renglón del recibo (vacío: "otro"). Se reusa con los recibos siguientes. */
@Entity
@Table(name = "mapeo_concepto_recibo")
public class MapeoConceptoRecibo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String clave;

    private Long conceptoSueldoId;

    protected MapeoConceptoRecibo() {
    }

    MapeoConceptoRecibo(String clave, Long conceptoSueldoId) {
        this.clave = clave;
        this.conceptoSueldoId = conceptoSueldoId;
    }

    public String getClave() {
        return clave;
    }

    public Long getConceptoSueldoId() {
        return conceptoSueldoId;
    }
}
