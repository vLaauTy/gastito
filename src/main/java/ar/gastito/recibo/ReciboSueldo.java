package ar.gastito.recibo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Un recibo de sueldo leído: de acá sale el ingreso (por el neto), que es su movimiento. A propósito no hay
 * campos para el CUIL, el CBU, el legajo ni el domicilio.
 */
@Entity
@Table(name = "recibo_sueldo")
public class ReciboSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long movimientoId;

    private String empleador;

    /** El día 1 del mes que liquida el recibo. */
    private LocalDate periodo;

    private LocalDate fechaPago;

    @Enumerated(EnumType.STRING)
    private TipoRecibo tipo;

    private BigDecimal bruto;

    private BigDecimal descuentos;

    private BigDecimal neto;

    /** Relativa a la carpeta de datos ("recibos/2026/10/15.pdf"). */
    private String archivo;

    /** Lo que no cerró al leerlo; null si todo cerró. */
    private String aviso;

    @OneToMany(mappedBy = "recibo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orden")
    private List<ReciboConcepto> conceptos = new ArrayList<>();

    protected ReciboSueldo() {
    }

    ReciboSueldo(Long movimientoId, ReciboLeido leido) {
        this.movimientoId = movimientoId;
        this.empleador = leido.empleador();
        this.periodo = leido.periodo().atDay(1);
        this.fechaPago = leido.fechaPago();
        this.tipo = leido.tipo();
        this.bruto = leido.bruto();
        this.descuentos = leido.descuentos();
        this.neto = leido.neto();
        this.aviso = leido.aviso();
        int orden = 0;
        for (ReciboLeido.Concepto c : leido.conceptos()) {
            conceptos.add(new ReciboConcepto(this, c.descripcion(), c.grupo(), c.importe(), orden++));
        }
    }

    void asociarArchivo(String ruta) {
        this.archivo = ruta;
    }

    public Long getId() {
        return id;
    }

    public Long getMovimientoId() {
        return movimientoId;
    }

    public String getEmpleador() {
        return empleador;
    }

    public YearMonth getPeriodo() {
        return YearMonth.from(periodo);
    }

    public LocalDate getFechaPago() {
        return fechaPago;
    }

    public TipoRecibo getTipo() {
        return tipo;
    }

    public BigDecimal getBruto() {
        return bruto;
    }

    public BigDecimal getDescuentos() {
        return descuentos;
    }

    public BigDecimal getNeto() {
        return neto;
    }

    public String getArchivo() {
        return archivo;
    }

    public String getAviso() {
        return aviso;
    }

    public List<ReciboConcepto> getConceptos() {
        return conceptos;
    }
}
