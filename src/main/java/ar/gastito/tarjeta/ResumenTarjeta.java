package ar.gastito.tarjeta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ar.gastito.catalogo.TarjetaCredito;

/**
 * Un resumen de la tarjeta: su cierre, su vencimiento (estimados hasta que el usuario los carga), el total que dice
 * el banco si lo cargó y el pago con que se debitó. Lo cargado en Gastito (compras y cuotas) no se guarda acá: se
 * calcula con {@link CalendarioTarjeta}.
 */
@Entity
@Table(name = "resumen_tarjeta")
public class ResumenTarjeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tarjeta_id")
    private TarjetaCredito tarjeta;

    /** El mes del cierre (día 1). */
    private LocalDate periodo;

    private LocalDate cierre;

    private LocalDate vencimiento;

    private boolean fechasConfirmadas;

    private BigDecimal totalReal;

    @Enumerated(EnumType.STRING)
    private EstadoResumenTarjeta estado;

    private Long pagoMovimientoId;

    private LocalDate preguntaFechasEn;

    private LocalDate fechasPospuestasHasta;

    private LocalDate avisoVencimientoEn;

    private LocalDate preguntaDebitoEn;

    private LocalDateTime creadoEn;

    protected ResumenTarjeta() {
    }

    ResumenTarjeta(TarjetaCredito tarjeta, CalendarioTarjeta.Fechas fechas, LocalDate hoy, LocalDateTime ahora) {
        this.tarjeta = tarjeta;
        this.periodo = fechas.periodo().atDay(1);
        this.creadoEn = ahora;
        this.estado = EstadoResumenTarjeta.ABIERTO;
        cambiarFechas(fechas, hoy);
    }

    CalendarioTarjeta.Fechas fechas() {
        return new CalendarioTarjeta.Fechas(getPeriodo(), cierre, vencimiento, fechasConfirmadas);
    }

    /** Fechas cargadas por el usuario, o una estimación nueva (si cambió el confirmado del que sale). */
    void cambiarFechas(CalendarioTarjeta.Fechas fechas, LocalDate hoy) {
        if (!fechas.vencimiento().isAfter(fechas.cierre())) {
            throw new IllegalArgumentException("El vencimiento tiene que ser después del cierre");
        }
        this.cierre = fechas.cierre();
        this.vencimiento = fechas.vencimiento();
        this.fechasConfirmadas = fechas.confirmadas();
        actualizarEstado(hoy);
    }

    /** Abierto hasta el cierre; después, cerrado hasta que se registra el pago (si se borró el pago, vuelve). */
    void actualizarEstado(LocalDate hoy) {
        if (pagoMovimientoId != null) {
            estado = EstadoResumenTarjeta.PAGADO;
        } else {
            estado = hoy.isAfter(cierre) ? EstadoResumenTarjeta.CERRADO : EstadoResumenTarjeta.ABIERTO;
        }
    }

    void cargarTotal(BigDecimal total) {
        if (total == null || total.signum() < 0) {
            throw new IllegalArgumentException("El total del resumen no puede ser negativo");
        }
        this.totalReal = total;
    }

    void pagar(Long movimientoId) {
        if (pagoMovimientoId != null) {
            throw new IllegalStateException("El resumen " + id + " ya está pagado");
        }
        this.pagoMovimientoId = movimientoId;
        this.estado = EstadoResumenTarjeta.PAGADO;
    }

    void preguntoFechas(LocalDate hoy) {
        this.preguntaFechasEn = hoy;
    }

    /** "Todavía no": se vuelve a preguntar desde ese día. */
    void posponerFechas(LocalDate hasta) {
        this.fechasPospuestasHasta = hasta;
    }

    void avisoVencimiento(LocalDate hoy) {
        this.avisoVencimientoEn = hoy;
    }

    void preguntoDebito(LocalDate hoy) {
        this.preguntaDebitoEn = hoy;
    }

    public Long getId() {
        return id;
    }

    public TarjetaCredito getTarjeta() {
        return tarjeta;
    }

    public YearMonth getPeriodo() {
        return YearMonth.from(periodo);
    }

    public LocalDate getCierre() {
        return cierre;
    }

    public LocalDate getVencimiento() {
        return vencimiento;
    }

    public boolean isFechasConfirmadas() {
        return fechasConfirmadas;
    }

    public BigDecimal getTotalReal() {
        return totalReal;
    }

    public EstadoResumenTarjeta getEstado() {
        return estado;
    }

    public Long getPagoMovimientoId() {
        return pagoMovimientoId;
    }

    public LocalDate getPreguntaFechasEn() {
        return preguntaFechasEn;
    }

    public LocalDate getFechasPospuestasHasta() {
        return fechasPospuestasHasta;
    }

    public LocalDate getAvisoVencimientoEn() {
        return avisoVencimientoEn;
    }

    public LocalDate getPreguntaDebitoEn() {
        return preguntaDebitoEn;
    }
}
