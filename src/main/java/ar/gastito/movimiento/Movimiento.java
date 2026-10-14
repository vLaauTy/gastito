package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

@Entity
@Table(name = "movimiento")
public class Movimiento {

    public static final String MONEDA_POR_DEFECTO = "ARS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Cuándo ocurrió, en hora de Argentina. */
    private LocalDateTime fechaHora;

    private BigDecimal monto;

    private String moneda = MONEDA_POR_DEFECTO;

    private String descripcion;

    @Enumerated(EnumType.STRING)
    private TipoMovimiento tipo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medio_pago_id")
    private MedioPago medioPago;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tarjeta_id")
    private Tarjeta tarjeta;

    private boolean juntada;

    private Integer cuotaActual;

    private Integer cuotaTotal;

    @Enumerated(EnumType.STRING)
    private Origen origen;

    @Enumerated(EnumType.STRING)
    private EstadoMovimiento estado;

    private String textoOriginal;

    /** Cuándo se registró en el sistema. */
    private LocalDateTime creadoEn;

    protected Movimiento() {
    }

    public static Movimiento pendiente(MovimientoInterpretado interpretado, Origen origen, String textoOriginal,
            LocalDateTime ahora) {
        Movimiento m = new Movimiento();
        m.fechaHora = interpretado.fechaHora();
        m.monto = interpretado.monto();
        m.descripcion = interpretado.descripcion();
        m.tipo = TipoMovimiento.GASTO;
        m.categoria = interpretado.categoria();
        m.medioPago = interpretado.medioPago();
        m.cuenta = interpretado.cuenta();
        m.tarjeta = interpretado.tarjeta();
        m.origen = origen;
        m.estado = EstadoMovimiento.PENDIENTE;
        m.textoOriginal = textoOriginal;
        m.creadoEn = ahora;
        return m;
    }

    public void confirmar() {
        cambiarEstadoDesdePendiente(EstadoMovimiento.CONFIRMADO);
    }

    public void cancelar() {
        cambiarEstadoDesdePendiente(EstadoMovimiento.CANCELADO);
    }

    private void cambiarEstadoDesdePendiente(EstadoMovimiento nuevo) {
        if (estado != EstadoMovimiento.PENDIENTE) {
            throw new IllegalStateException("El movimiento " + id + " ya está " + estado);
        }
        estado = nuevo;
    }

    public boolean isPendiente() {
        return estado == EstadoMovimiento.PENDIENTE;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public String getMoneda() {
        return moneda;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public TipoMovimiento getTipo() {
        return tipo;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public MedioPago getMedioPago() {
        return medioPago;
    }

    public Cuenta getCuenta() {
        return cuenta;
    }

    public Tarjeta getTarjeta() {
        return tarjeta;
    }

    public boolean isJuntada() {
        return juntada;
    }

    public Integer getCuotaActual() {
        return cuotaActual;
    }

    public Integer getCuotaTotal() {
        return cuotaTotal;
    }

    public Origen getOrigen() {
        return origen;
    }

    public EstadoMovimiento getEstado() {
        return estado;
    }

    public String getTextoOriginal() {
        return textoOriginal;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }
}
