package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;

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
    private TarjetaCredito tarjeta;

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

    /** Categorías posibles, en orden, mientras el usuario no eligió una. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "movimiento_categoria_candidata",
            joinColumns = @JoinColumn(name = "movimiento_id"),
            inverseJoinColumns = @JoinColumn(name = "categoria_id"))
    @OrderColumn(name = "orden")
    private List<Categoria> candidatas = new ArrayList<>();

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
        if (m.categoria == null) {
            m.candidatas.addAll(interpretado.candidatas());
        }
        return m;
    }

    /** Tiene monto y categoría: se puede guardar. */
    public boolean isCompleto() {
        return monto != null && categoria != null;
    }

    public void confirmar() {
        if (!isCompleto()) {
            throw new MovimientoIncompletoException(id);
        }
        cambiarEstadoDesdePendiente(EstadoMovimiento.CONFIRMADO);
    }

    public void elegirCategoria(Categoria elegida) {
        verificarPendiente();
        this.categoria = elegida;
    }

    public void cambiarMonto(BigDecimal nuevo) {
        verificarPendiente();
        if (nuevo == null || nuevo.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        this.monto = nuevo;
    }

    public void cambiarDescripcion(String nueva) {
        verificarPendiente();
        this.descripcion = nueva;
    }

    public void cambiarFechaHora(LocalDateTime nueva) {
        verificarPendiente();
        this.fechaHora = nueva;
    }

    /**
     * Con tarjeta de crédito se asocia la tarjeta y se saca la cuenta (la cuenta es la que paga el resumen).
     * Con otro medio, se saca la tarjeta.
     */
    public void cambiarMedioPago(MedioPago nuevo, TarjetaCredito tarjetaCredito) {
        verificarPendiente();
        this.medioPago = nuevo;
        if (nuevo != null && nuevo.esTarjetaDeCredito()) {
            this.tarjeta = tarjetaCredito;
            this.cuenta = null;
        } else {
            this.tarjeta = null;
        }
    }

    private void verificarPendiente() {
        if (estado != EstadoMovimiento.PENDIENTE) {
            throw new IllegalStateException("El movimiento " + id + " ya está " + estado + ": no se puede editar");
        }
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

    public TarjetaCredito getTarjeta() {
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

    public List<Categoria> getCandidatas() {
        return Collections.unmodifiableList(candidatas);
    }
}
