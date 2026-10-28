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

    protected ReciboConcepto() {
    }

    ReciboConcepto(ReciboSueldo recibo, String descripcion, GrupoConcepto grupo, BigDecimal importe, int orden) {
        this.recibo = recibo;
        this.descripcion = descripcion;
        this.grupo = grupo;
        this.importe = importe;
        this.orden = orden;
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
