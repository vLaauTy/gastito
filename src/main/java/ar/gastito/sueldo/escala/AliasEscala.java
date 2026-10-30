package ar.gastito.sueldo.escala;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un nombre de fila de la hoja del sindicato y a qué corresponde ("A.I.S." → Asignación integración social). */
@Entity
@Table(name = "alias_escala")
public class AliasEscala {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String alias;

    private String clave;

    @Enumerated(EnumType.STRING)
    private DestinoAlias destino;

    private Long conceptoId;

    protected AliasEscala() {
    }

    public AliasEscala(String alias, DestinoAlias destino, Long conceptoId) {
        this.alias = alias;
        this.clave = NombreFila.clave(alias);
        this.destino = destino;
        this.conceptoId = destino == DestinoAlias.CONCEPTO ? conceptoId : null;
    }

    public Long getId() {
        return id;
    }

    public String getAlias() {
        return alias;
    }

    public String getClave() {
        return clave;
    }

    public DestinoAlias getDestino() {
        return destino;
    }

    public Long getConceptoId() {
        return conceptoId;
    }
}
