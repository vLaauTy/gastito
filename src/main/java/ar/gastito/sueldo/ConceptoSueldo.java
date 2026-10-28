package ar.gastito.sueldo;

import java.math.BigDecimal;
import java.math.MathContext;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un concepto del sueldo. El factor de las horas es una fracción (8/60) para que la cuenta dé exacta. */
@Entity
@Table(name = "concepto_sueldo")
public class ConceptoSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @Enumerated(EnumType.STRING)
    private TipoConceptoSueldo tipo;

    private Integer factorNum;

    private Integer factorDen;

    private int orden;

    private boolean activo = true;

    protected ConceptoSueldo() {
    }

    public ConceptoSueldo(String nombre, TipoConceptoSueldo tipo, Integer factorNum, Integer factorDen, int orden) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.orden = orden;
        definirFactor(factorNum, factorDen);
    }

    /** Para los tests, que arman conceptos sin pasar por la base. */
    public static ConceptoSueldo conId(Long id, ConceptoSueldo base) {
        base.id = id;
        return base;
    }

    private void definirFactor(Integer num, Integer den) {
        if (tipo == TipoConceptoSueldo.HORAS) {
            if (num == null || den == null || num <= 0 || den <= 0) {
                throw new IllegalArgumentException("Las horas necesitan un factor mayor a cero (por ejemplo 3/2)");
            }
            this.factorNum = num;
            this.factorDen = den;
        } else {
            this.factorNum = null;
            this.factorDen = null;
        }
    }

    public void editar(String nombre, TipoConceptoSueldo tipo, Integer factorNum, Integer factorDen, int orden,
            boolean activo) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.orden = orden;
        this.activo = activo;
        definirFactor(factorNum, factorDen);
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public TipoConceptoSueldo getTipo() {
        return tipo;
    }

    public Integer getFactorNum() {
        return factorNum;
    }

    public Integer getFactorDen() {
        return factorDen;
    }

    public int getOrden() {
        return orden;
    }

    public boolean isActivo() {
        return activo;
    }

    /** El factor como decimal para mostrarlo (1,5; 2; 0,1333); null si no son horas. */
    public BigDecimal factorDecimal() {
        return factorNum == null ? null
                : BigDecimal.valueOf(factorNum).divide(BigDecimal.valueOf(factorDen), new MathContext(6))
                        .stripTrailingZeros();
    }
}
