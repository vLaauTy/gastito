package ar.gastito.catalogo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Nombre raro de un comercio en el resumen de la tarjeta (se usa desde la etapa 7). */
@Entity
@Table(name = "alias_comercio")
public class AliasComercio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String patron;

    private String nombreComercio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    private boolean gastoFijo;

    protected AliasComercio() {
    }

    public Long getId() {
        return id;
    }

    public String getPatron() {
        return patron;
    }

    public String getNombreComercio() {
        return nombreComercio;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public boolean isGastoFijo() {
        return gastoFijo;
    }
}
