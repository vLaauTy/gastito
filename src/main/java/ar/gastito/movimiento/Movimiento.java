package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

    /** Solo en los ingresos: sueldo, aguinaldo o extra. */
    @Enumerated(EnumType.STRING)
    private ClaseIngreso claseIngreso;

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

    /** De un ticket: el comercio tal como lo leyó la IA (la descripción arranca igual, pero se puede editar). */
    private String comercio;

    /** De un ticket: la sucursal ("5601"), para cruzarlo con el resumen de la tarjeta. */
    private String sucursal;

    /** De un ticket: el SUBTOTAL impreso, si figura. */
    private BigDecimal subtotalImpreso;

    /** De un ticket: el total de descuentos impreso (negativo), si figura. */
    private BigDecimal descuentosImpresos;

    /** De un ticket: las líneas que la IA no pudo leer, una por renglón. */
    private String lineasIlegibles;

    /** De un ticket: la foto, relativa a la carpeta de datos (tickets/AAAA/MM/<id>.jpg). */
    private String fotoTicket;

    /** De un ticket: los productos y descuentos, en el orden impreso. */
    @OneToMany(mappedBy = "movimiento", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orden")
    private List<MovimientoItem> items = new ArrayList<>();

    /** El largo de la columna texto_original: un mensaje o una transcripción más larga se recorta. */
    static final int LARGO_MAXIMO_TEXTO_ORIGINAL = 1000;

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
        m.textoOriginal = textoOriginal != null && textoOriginal.length() > LARGO_MAXIMO_TEXTO_ORIGINAL
                ? textoOriginal.substring(0, LARGO_MAXIMO_TEXTO_ORIGINAL)
                : textoOriginal;
        m.creadoEn = ahora;
        if (m.categoria == null) {
            m.candidatas.addAll(interpretado.candidatas());
        }
        return m;
    }

    /**
     * Un gasto leído de la foto de un ticket, con sus productos. La foto se asocia después, cuando el movimiento
     * ya tiene id.
     *
     * @param textoOriginal el texto que acompañaba la foto, o null
     */
    public static Movimiento deTicket(MovimientoInterpretado interpretado, DatosTicket ticket, String textoOriginal,
            LocalDateTime ahora) {
        Movimiento m = pendiente(interpretado, Origen.TICKET, textoOriginal, ahora);
        m.comercio = ticket.comercio();
        m.sucursal = ticket.sucursal();
        m.subtotalImpreso = ticket.subtotalImpreso();
        m.descuentosImpresos = ticket.descuentosImpresos();
        String ilegibles = String.join("\n", ticket.lineasIlegibles());
        m.lineasIlegibles = ilegibles.isEmpty() ? null
                : ilegibles.length() > LARGO_MAXIMO_TEXTO_ORIGINAL
                        ? ilegibles.substring(0, LARGO_MAXIMO_TEXTO_ORIGINAL)
                        : ilegibles;
        for (ItemTicket item : ticket.items()) {
            m.items.add(new MovimientoItem(m, m.items.size(), item));
        }
        return m;
    }

    /**
     * Un ingreso pendiente, hasta que el usuario lo guarde. Va a una cuenta, o en efectivo (sin cuenta y con el
     * medio de pago efectivo); sin ninguno de los dos, queda sin especificar.
     */
    public static Movimiento ingreso(ClaseIngreso clase, BigDecimal monto, String descripcion, Cuenta cuenta,
            MedioPago efectivo, LocalDateTime fechaHora, Origen origen, String textoOriginal, LocalDateTime ahora) {
        Movimiento m = new Movimiento();
        m.tipo = TipoMovimiento.INGRESO;
        m.claseIngreso = clase;
        m.monto = monto;
        m.descripcion = descripcion;
        m.cuenta = cuenta;
        m.medioPago = cuenta == null ? efectivo : null;
        m.fechaHora = fechaHora;
        m.origen = origen;
        m.estado = EstadoMovimiento.PENDIENTE;
        m.textoOriginal = textoOriginal;
        m.creadoEn = ahora;
        return m;
    }

    /** Tiene monto y, si es un gasto, categoría: se puede guardar. */
    public boolean isCompleto() {
        return monto != null && (tipo != TipoMovimiento.GASTO || categoria != null);
    }

    public boolean isIngreso() {
        return tipo == TipoMovimiento.INGRESO;
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

    /** Borra un ingreso ya guardado: queda CANCELADO, así no suma pero queda el registro. */
    public void anularIngreso() {
        if (!isIngreso() || estado != EstadoMovimiento.CONFIRMADO) {
            throw new IllegalStateException("El movimiento " + id + " no es un ingreso guardado");
        }
        estado = EstadoMovimiento.CANCELADO;
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

    /** @param ruta relativa a la carpeta de datos */
    public void asociarFotoTicket(String ruta) {
        if (origen != Origen.TICKET) {
            throw new IllegalStateException("El movimiento " + id + " no viene de un ticket");
        }
        this.fotoTicket = ruta;
    }

    /** La foto se borró del disco (por ejemplo, porque se canceló el ticket). */
    public void quitarFotoTicket() {
        this.fotoTicket = null;
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

    public ClaseIngreso getClaseIngreso() {
        return claseIngreso;
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

    public String getComercio() {
        return comercio;
    }

    public String getFotoTicket() {
        return fotoTicket;
    }

    public String getSucursal() {
        return sucursal;
    }

    public BigDecimal getSubtotalImpreso() {
        return subtotalImpreso;
    }

    public BigDecimal getDescuentosImpresos() {
        return descuentosImpresos;
    }

    public List<String> getLineasIlegibles() {
        return lineasIlegibles == null ? List.of() : List.of(lineasIlegibles.split("\n"));
    }

    public List<MovimientoItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
