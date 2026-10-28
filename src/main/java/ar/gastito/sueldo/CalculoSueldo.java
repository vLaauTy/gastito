package ar.gastito.sueldo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** El cálculo guardado de un mes trabajado, con lo que dio cada concepto (no cambia si después cambia la escala). */
@Entity
@Table(name = "calculo_sueldo")
public class CalculoSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** El día 1 del mes trabajado. */
    private LocalDate periodo;

    private Long escalaId;

    private BigDecimal valorHora;

    private BigDecimal bruto;

    private BigDecimal descuentos;

    private BigDecimal netoHaberes;

    private BigDecimal noRemunerativo;

    private BigDecimal netoTotal;

    private LocalDateTime calculadoEn;

    // Son una docena de renglones y se leen siempre juntos (el bot, el dashboard): sin cargarlos aparte.
    @OneToMany(mappedBy = "calculo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("orden")
    private List<Linea> lineas = new ArrayList<>();

    protected CalculoSueldo() {
    }

    public CalculoSueldo(YearMonth periodo, Long escalaId, CalculadoraSueldo.Resultado r, LocalDateTime ahora) {
        this.periodo = periodo.atDay(1);
        reemplazar(escalaId, r, ahora);
    }

    void reemplazar(Long escalaId, CalculadoraSueldo.Resultado r, LocalDateTime ahora) {
        this.escalaId = escalaId;
        this.valorHora = r.valorHora();
        this.bruto = r.bruto();
        this.descuentos = r.descuentos();
        this.netoHaberes = r.netoHaberes();
        this.noRemunerativo = r.noRemunerativo();
        this.netoTotal = r.netoTotal();
        this.calculadoEn = ahora;
        lineas.clear();
        int orden = 0;
        for (CalculadoraSueldo.Linea l : r.lineas()) {
            lineas.add(new Linea(this, l, orden++));
        }
    }

    public Long getId() {
        return id;
    }

    public YearMonth getPeriodo() {
        return YearMonth.from(periodo);
    }

    public Long getEscalaId() {
        return escalaId;
    }

    public BigDecimal getValorHora() {
        return valorHora;
    }

    public BigDecimal getBruto() {
        return bruto;
    }

    public BigDecimal getDescuentos() {
        return descuentos;
    }

    public BigDecimal getNetoHaberes() {
        return netoHaberes;
    }

    public BigDecimal getNoRemunerativo() {
        return noRemunerativo;
    }

    public BigDecimal getNetoTotal() {
        return netoTotal;
    }

    public List<Linea> getLineas() {
        return lineas;
    }

    @Entity
    @Table(name = "calculo_sueldo_linea")
    public static class Linea {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "calculo_id")
        private CalculoSueldo calculo;

        private Long conceptoId;

        private String nombre;

        @Enumerated(EnumType.STRING)
        private TipoConceptoSueldo tipo;

        private BigDecimal cantidad;

        private BigDecimal valor;

        private BigDecimal importe;

        private boolean ajustado;

        private int orden;

        protected Linea() {
        }

        Linea(CalculoSueldo calculo, CalculadoraSueldo.Linea l, int orden) {
            this.calculo = calculo;
            this.conceptoId = l.conceptoId();
            this.nombre = l.nombre();
            this.tipo = l.tipo();
            this.cantidad = l.cantidad();
            this.valor = l.valor();
            this.importe = l.importe();
            this.ajustado = l.ajustado();
            this.orden = orden;
        }

        public Long getConceptoId() {
            return conceptoId;
        }

        public String getNombre() {
            return nombre;
        }

        public TipoConceptoSueldo getTipo() {
            return tipo;
        }

        public BigDecimal getCantidad() {
            return cantidad;
        }

        public BigDecimal getValor() {
            return valor;
        }

        public BigDecimal getImporte() {
            return importe;
        }

        public boolean isAjustado() {
            return ajustado;
        }
    }
}
