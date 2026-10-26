package ar.gastito.juntada;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.movimiento.Movimiento;

/**
 * Pagué por un grupo. El movimiento es mi parte (lo único que cuenta como gasto); acá va el total que pagué, desde
 * dónde (lo que se compara con un aviso o con el resumen de la tarjeta) y lo que me debe cada uno.
 */
@Entity
@Table(name = "juntada")
public class Juntada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movimiento_id")
    private Movimiento movimiento;

    private BigDecimal totalPagado;

    private int personas;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medio_pago_id")
    private MedioPago medioPago;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tarjeta_id")
    private TarjetaCredito tarjeta;

    private LocalDateTime creadoEn;

    @OneToMany(mappedBy = "juntada", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orden")
    private List<Participante> participantes = new ArrayList<>();

    protected Juntada() {
    }

    /**
     * El total en partes iguales. Los demás se llaman como se dijo ("Martín") o, si no, "Persona 2", "Persona 3"…
     * (la 1 soy yo).
     *
     * @param nombres de los demás, en orden; pueden ser menos que personas - 1
     */
    Juntada(Movimiento movimiento, BigDecimal totalPagado, int personas, List<String> nombres, LocalDateTime ahora) {
        this.movimiento = movimiento;
        this.totalPagado = totalPagado;
        this.personas = personas;
        this.creadoEn = ahora;
        DivisionJuntada.Reparto reparto = DivisionJuntada.enPartesIguales(totalPagado, personas);
        for (int i = 0; i < personas - 1; i++) {
            String nombre = i < nombres.size() && !nombres.get(i).isBlank() ? nombres.get(i) : "Persona " + (i + 2);
            participantes.add(new Participante(this, i, nombre, reparto.otras().get(i)));
        }
        movimiento.prepararJuntada(reparto.miParte());
    }

    /** Desde dónde salió el total: lo que tiene el movimiento al guardarse. */
    void anotarBilletera() {
        this.medioPago = movimiento.getMedioPago();
        this.cuenta = movimiento.getCuenta();
        this.tarjeta = movimiento.getTarjeta();
    }

    /** Se pagó otro total: se reparte de nuevo (las partes fijas quedan). */
    void cambiarTotal(BigDecimal nuevo) {
        DivisionJuntada.Reparto reparto = DivisionJuntada.repartir(nuevo, partes());
        this.totalPagado = nuevo.setScale(2, RoundingMode.HALF_UP);
        aplicar(reparto);
    }

    /** La parte de uno cambió a mano: las demás libres se reparten de nuevo para que el total siga cerrando. */
    void cambiarParte(Participante participante, BigDecimal monto) {
        if (monto == null || monto.signum() < 0) {
            throw new JuntadaInvalidaException("La parte de alguien no puede ser negativa");
        }
        if (monto.compareTo(participante.getMontoPagado()) < 0) {
            throw new JuntadaInvalidaException(participante.getNombre() + " ya te pagó más que eso");
        }
        List<DivisionJuntada.Parte> partes = new ArrayList<>();
        for (Participante p : participantes) {
            partes.add(p == participante ? new DivisionJuntada.Parte(monto, true)
                    : new DivisionJuntada.Parte(p.getMonto(), p.isFija()));
        }
        DivisionJuntada.Reparto reparto = DivisionJuntada.repartir(totalPagado, partes);
        participante.cambiarAMano(monto.setScale(2, RoundingMode.HALF_UP));
        aplicar(reparto);
    }

    private List<DivisionJuntada.Parte> partes() {
        return participantes.stream().map(p -> new DivisionJuntada.Parte(p.getMonto(), p.isFija())).toList();
    }

    private void aplicar(DivisionJuntada.Reparto reparto) {
        for (int i = 0; i < participantes.size(); i++) {
            Participante p = participantes.get(i);
            if (!p.isFija()) {
                p.repartido(reparto.otras().get(i));
            }
        }
        movimiento.ajustarParteDeJuntada(reparto.miParte());
    }

    /** Lo que todavía me deben: lo que le falta a cada uno (sin lo pagado, lo saldado ni lo perdido). */
    public BigDecimal aCobrar() {
        return participantes.stream().map(Participante::getRestante).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Participante participante(Long participanteId) {
        return participantes.stream().filter(p -> p.getId().equals(participanteId)).findFirst()
                .orElseThrow(() -> new JuntadaInvalidaException("Esa persona no está en la juntada"));
    }

    public Long getId() {
        return id;
    }

    public Movimiento getMovimiento() {
        return movimiento;
    }

    public BigDecimal getTotalPagado() {
        return totalPagado;
    }

    public int getPersonas() {
        return personas;
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

    public List<Participante> getParticipantes() {
        return Collections.unmodifiableList(participantes);
    }
}
