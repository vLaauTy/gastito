package ar.gastito.recibo;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Un renglón del recibo: descripción, grupo e importe (siempre positivo; el grupo dice si suma o resta). */
@Entity
@Table(name = "recibo_concepto")
public class ReciboConcepto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recibo_id")
    private ReciboSueldo recibo;

    private String descripcion;

    @Enumerated(EnumType.STRING)
    private GrupoConcepto grupo;

    private BigDecimal importe;

    private int orden;

    /** El concepto del sueldo al que corresponde; null si es "otro" o no se pudo saber. */
    private Long conceptoSueldoId;

    protected ReciboConcepto() {
    }

    ReciboConcepto(ReciboSueldo recibo, String descripcion, GrupoConcepto grupo, BigDecimal importe, int orden,
            Long conceptoSueldoId) {
        this.recibo = recibo;
        this.descripcion = descripcion;
        this.grupo = grupo;
        this.importe = importe;
        this.orden = orden;
        this.conceptoSueldoId = conceptoSueldoId;
    }

    public Long getConceptoSueldoId() {
        return conceptoSueldoId;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public GrupoConcepto getGrupo() {
        return grupo;
    }

    public BigDecimal getImporte() {
        return importe;
    }

    public int getOrden() {
        return orden;
    }
}
