package ar.gastito.cuota;

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

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.TarjetaCredito;

/**
 * Una compra en cuotas: cada cuota es un movimiento aparte, generado automáticamente y ya confirmado, con "(cuota X
 * de N)" en la descripción. La cuota 1 es la que interpretó la IA; las siguientes se generan al guardarla.
 */
@Entity
@Table(name = "plan_cuotas")
public class PlanCuotas {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String descripcion;

    private BigDecimal total;

    private int cantidad;

    private BigDecimal valorCuota;

    private LocalDateTime fechaCompra;

    /** El mes de la primera cuota (día 1): el de la compra, o el siguiente según gastito.cuotas.primera-cuota. */
    private LocalDate primerMes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tarjeta_id")
    private TarjetaCredito tarjeta;

    @Enumerated(EnumType.STRING)
    private EstadoPlanCuotas estado = EstadoPlanCuotas.ACTIVO;

    private LocalDateTime creadoEn;

    protected PlanCuotas() {
    }

    public PlanCuotas(String descripcion, BigDecimal total, int cantidad, BigDecimal valorCuota,
            LocalDateTime fechaCompra, YearMonth primerMes, Categoria categoria, TarjetaCredito tarjeta,
            LocalDateTime ahora) {
        if (cantidad < 2) {
            throw new IllegalArgumentException("Un plan de cuotas tiene que ser de al menos 2 cuotas");
        }
        if (total == null || total.signum() <= 0 || valorCuota == null || valorCuota.signum() <= 0) {
            throw new IllegalArgumentException("El total y el valor de la cuota tienen que ser mayores a cero");
        }
        this.descripcion = descripcion;
        this.total = total;
        this.cantidad = cantidad;
        this.valorCuota = valorCuota;
        this.fechaCompra = fechaCompra;
        this.primerMes = primerMes.atDay(1);
        this.categoria = categoria;
        this.tarjeta = tarjeta;
        this.creadoEn = ahora;
    }

    /** El día de la cuota N: el mismo día del mes que la compra, o el último si el mes no lo tiene. */
    public LocalDateTime fechaDeCuota(int numero) {
        YearMonth mes = YearMonth.from(primerMes).plusMonths(numero - 1L);
        int dia = Math.min(fechaCompra.getDayOfMonth(), mes.lengthOfMonth());
        return mes.atDay(dia).atTime(fechaCompra.toLocalTime());
    }

    /** Al guardar la primera cuota (ya confirmada): el plan toma lo que quedó, no lo que había al interpretar. */
    void actualizarValores(BigDecimal valorCuotaConfirmado, Categoria categoriaConfirmada,
            TarjetaCredito tarjetaConfirmada) {
        this.valorCuota = valorCuotaConfirmado;
        this.total = valorCuotaConfirmado.multiply(BigDecimal.valueOf(cantidad));
        this.categoria = categoriaConfirmada;
        this.tarjeta = tarjetaConfirmada;
    }

    public void cancelar() {
        if (estado != EstadoPlanCuotas.ACTIVO) {
            throw new IllegalStateException("El plan " + id + " ya está " + estado);
        }
        estado = EstadoPlanCuotas.CANCELADO;
    }

    public Long getId() {
        return id;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public int getCantidad() {
        return cantidad;
    }

    public BigDecimal getValorCuota() {
        return valorCuota;
    }

    public LocalDateTime getFechaCompra() {
        return fechaCompra;
    }

    public LocalDate getPrimerMes() {
        return primerMes;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public TarjetaCredito getTarjeta() {
        return tarjeta;
    }

    public EstadoPlanCuotas getEstado() {
        return estado;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }
}
