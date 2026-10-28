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
 * Un recibo de sueldo leído. No crea el ingreso: es un control del sueldo cobrado, al que queda vinculado
 * ({@code movimientoId}) cuando se lo vincula a mano; si no, se lo busca por el período. A propósito no hay campos
 * para el CUIL, el CBU, el legajo ni el domicilio.
 */
@Entity
@Table(name = "recibo_sueldo")
public class ReciboSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** El sueldo cobrado al que se vinculó a mano; null si todavía no (se busca por período). */
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

    /** @param conceptosMapeados el concepto del sueldo de cada renglón (null: "otro"), en el orden de la lista */
    ReciboSueldo(ReciboLeido leido, List<Long> conceptosMapeados) {
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
            conceptos.add(new ReciboConcepto(this, c.descripcion(), c.grupo(), c.importe(), orden,
                    conceptosMapeados == null ? null : conceptosMapeados.get(orden)));
            orden++;
        }
    }

    void asociarArchivo(String ruta) {
        this.archivo = ruta;
    }

    void vincular(Long movimientoId) {
        this.movimientoId = movimientoId;
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
