package ar.gastito.prestamo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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

/**
 * Plata que le presté a alguien y me devuelve en cuotas. No es gasto mío: es plata a cobrar. Si salió del mismo pago
 * que un gasto mío ("pagué 293.000 de vuelos; 30.000 son de Martín"), {@link #movimiento} es ese gasto (con mi parte)
 * y el préstamo vale solo mientras el gasto esté guardado.
 */
@Entity
@Table(name = "prestamo")
public class Prestamo {

    static final int LARGO_MAXIMO_PERSONA = 60;
    static final int LARGO_MAXIMO_TEXTO = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String persona;

    private String descripcion;

    private BigDecimal monto;

    @Column(name = "cuotas")
    private int cantidadCuotas;

    private BigDecimal valorCuota;

    private LocalDate primerMes;

    private int dia;

    private LocalDate fecha;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medio_pago_id")
    private MedioPago medioPago;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movimiento_id")
    private Movimiento movimiento;

    private Long viajeId;

    @Enumerated(EnumType.STRING)
    private EstadoPrestamo estado;

    private String textoOriginal;

    private LocalDateTime creadoEn;

    @OneToMany(mappedBy = "prestamo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numero")
    private List<CuotaPrestamo> cuotas = new ArrayList<>();

    protected Prestamo() {
    }

    /**
     * @param primerMes el mes de la primera cuota
     * @param dia       el día aproximado en que paga cada cuota (si el mes no lo tiene, el último)
     * @param movimiento el gasto del que salió, o null si se cargó solo
     */
    Prestamo(String persona, String descripcion, PlanPrestamo.Plan plan, YearMonth primerMes, int dia,
            LocalDate fecha, Cuenta cuenta, MedioPago medioPago, Movimiento movimiento, Long viajeId,
            EstadoPrestamo estado, String textoOriginal, LocalDateTime ahora) {
        if (persona == null || persona.isBlank()) {
            throw new PrestamoInvalidoException("Falta a quién le prestaste");
        }
        if (dia < 1 || dia > 31) {
            throw new PrestamoInvalidoException("El día tiene que ser del 1 al 31");
        }
        String limpia = persona.strip();
        this.persona = limpia.length() > LARGO_MAXIMO_PERSONA ? limpia.substring(0, LARGO_MAXIMO_PERSONA) : limpia;
        this.descripcion = descripcion;
        this.primerMes = primerMes.atDay(1);
        this.dia = dia;
        this.fecha = fecha;
        this.cuenta = cuenta;
        this.medioPago = medioPago;
        this.movimiento = movimiento;
        this.viajeId = viajeId;
        this.estado = estado;
        this.textoOriginal = textoOriginal != null && textoOriginal.length() > LARGO_MAXIMO_TEXTO
                ? textoOriginal.substring(0, LARGO_MAXIMO_TEXTO) : textoOriginal;
        this.creadoEn = ahora;
        planificar(plan);
    }

    private void planificar(PlanPrestamo.Plan plan) {
        this.monto = plan.monto();
        this.cantidadCuotas = plan.cuotas();
        this.valorCuota = plan.valorCuota();
        cuotas.clear();
        YearMonth mes = YearMonth.from(primerMes);
        for (int i = 0; i < plan.montos().size(); i++) {
            cuotas.add(new CuotaPrestamo(this, i + 1, vencimiento(mes.plusMonths(i), dia), plan.montos().get(i)));
        }
    }

    /** El día del mes en que vence una cuota; si el mes no lo tiene, el último. */
    static LocalDate vencimiento(YearMonth mes, int dia) {
        return mes.atDay(Math.min(dia, mes.lengthOfMonth()));
    }

    /** Vale: está confirmado y, si salió de un gasto, el gasto está guardado. */
    public boolean isVigente() {
        return estado != EstadoPrestamo.PENDIENTE
                && (movimiento == null || movimiento.getEstado() == EstadoMovimiento.CONFIRMADO);
    }

    void confirmar() {
        if (estado == EstadoPrestamo.PENDIENTE) {
            this.estado = EstadoPrestamo.ACTIVO;
        }
    }

    /** Otro total (se corrigió en la tarjeta): las cuotas se rehacen, si todavía no me pagó ninguna. */
    void cambiarMonto(BigDecimal nuevo) {
        if (cuotas.stream().anyMatch(c -> c.getEstado() == EstadoCuota.COBRADA)) {
            throw new PrestamoInvalidoException(persona + " ya te pagó una cuota: el total no se cambia");
        }
        planificar(PlanPrestamo.de(nuevo, cantidadCuotas, null));
    }

    /**
     * Me pagó {@code pago}: cubre las cuotas pendientes que alcanza (ver {@link PlanPrestamo#aplicar}) y, si pagó de
     * más o de menos, las que siguen se acomodan. Si después de la última todavía me debe algo, se agrega una cuota
     * al mes siguiente. Sin cuotas pendientes, queda terminado.
     */
    PlanPrestamo.Aplicacion cobrar(BigDecimal pago, Long devolucionId, LocalDateTime ahora) {
        if (estado != EstadoPrestamo.ACTIVO) {
            throw new PrestamoInvalidoException(estado == EstadoPrestamo.TERMINADO
                    ? persona + " ya te pagó todo" : "Primero guardá el préstamo");
        }
        List<CuotaPrestamo> porCobrar = porCobrar();
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(porCobrar.stream().map(CuotaPrestamo::getMonto).toList(),
                pago);
        for (int i = 0; i < a.cubiertas(); i++) {
            porCobrar.get(i).cobrar(a.cobrado().get(i), devolucionId, ahora);
        }
        List<CuotaPrestamo> siguientes = porCobrar.subList(a.cubiertas(), porCobrar.size());
        for (int i = 0; i < a.siguientes().size(); i++) {
            if (i < siguientes.size()) {
                siguientes.get(i).cambiarMonto(a.siguientes().get(i));
            } else {
                CuotaPrestamo ultima = cuotas.get(cuotas.size() - 1);
                YearMonth mes = YearMonth.from(ultima.getVence()).plusMonths(1);
                cuotas.add(new CuotaPrestamo(this, ultima.getNumero() + 1, vencimiento(mes, dia),
                        a.siguientes().get(i)));
                cantidadCuotas = cuotas.size();
            }
        }
        if (porCobrar().isEmpty()) {
            this.estado = EstadoPrestamo.TERMINADO;
        }
        return a;
    }

    /** Las cuotas que todavía me debe (pendientes o atrasadas), de la más vieja a la más nueva. */
    List<CuotaPrestamo> porCobrar() {
        return cuotas.stream().filter(c -> c.getEstado().porCobrar()).toList();
    }

    public Optional<CuotaPrestamo> proxima() {
        return cuotas.stream().filter(c -> c.getEstado().porCobrar()).findFirst();
    }

    public BigDecimal getCobrado() {
        return cuotas.stream().filter(c -> c.getEstado() == EstadoCuota.COBRADA).map(CuotaPrestamo::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Lo que todavía me debe. */
    public BigDecimal getRestante() {
        return porCobrar().stream().map(CuotaPrestamo::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long getCuotasCobradas() {
        return cuotas.stream().filter(c -> c.getEstado() == EstadoCuota.COBRADA).count();
    }

    void asociarViaje(Long viajeId) {
        this.viajeId = viajeId;
    }

    public Long getId() {
        return id;
    }

    public String getPersona() {
        return persona;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public int getCantidadCuotas() {
        return cantidadCuotas;
    }

    public BigDecimal getValorCuota() {
        return valorCuota;
    }

    public YearMonth getPrimerMes() {
        return YearMonth.from(primerMes);
    }

    public int getDia() {
        return dia;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public Cuenta getCuenta() {
        return cuenta;
    }

    public MedioPago getMedioPago() {
        return medioPago;
    }

    public Movimiento getMovimiento() {
        return movimiento;
    }

    public Long getViajeId() {
        return viajeId;
    }

    public EstadoPrestamo getEstado() {
        return estado;
    }

    public String getTextoOriginal() {
        return textoOriginal;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public List<CuotaPrestamo> getCuotas() {
        return cuotas;
    }
}
