package ar.gastito.sueldo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

/**
 * Los valores que rigen desde una fecha: la hora normal y, por concepto, el monto fijo (o el porcentaje si es un
 * descuento). Al cambiar la paritaria se carga una escala nueva y las anteriores quedan.
 */
@Entity
@Table(name = "escala_sueldo")
public class EscalaSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate vigenteDesde;

    private BigDecimal valorHora;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "escala_valor", joinColumns = @JoinColumn(name = "escala_id"))
    @MapKeyColumn(name = "concepto_id")
    @Column(name = "valor")
    private Map<Long, BigDecimal> valores = new HashMap<>();

    protected EscalaSueldo() {
    }

    public EscalaSueldo(LocalDate vigenteDesde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
        this.vigenteDesde = vigenteDesde;
        this.valorHora = valorHora;
        this.valores = new HashMap<>(valores);
    }

    public void editar(LocalDate vigenteDesde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
        this.vigenteDesde = vigenteDesde;
        this.valorHora = valorHora;
        this.valores.clear();
        this.valores.putAll(valores);
    }

    public Long getId() {
        return id;
    }

    public LocalDate getVigenteDesde() {
        return vigenteDesde;
    }

    public BigDecimal getValorHora() {
        return valorHora;
    }

    public Map<Long, BigDecimal> getValores() {
        return valores;
    }

    /** El monto (o porcentaje) del concepto en esta escala; cero si no se cargó. */
    public BigDecimal valorDe(Long conceptoId) {
        return valores.getOrDefault(conceptoId, BigDecimal.ZERO);
    }
}
