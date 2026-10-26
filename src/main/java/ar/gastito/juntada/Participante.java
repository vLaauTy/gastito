package ar.gastito.juntada;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
import jakarta.persistence.Table;

/** Uno de los demás en una juntada (yo no): cuánto me debe y si ya me pagó. */
@Entity
@Table(name = "juntada_participante")
public class Participante {

    static final int LARGO_MAXIMO_NOMBRE = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "juntada_id")
    private Juntada juntada;

    private int orden;

    private String nombre;

    private BigDecimal monto;

    /** Se cambió a mano: al repartir de nuevo, no se toca. */
    @Column(name = "monto_a_mano")
    private boolean montoAMano;

    @Enumerated(EnumType.STRING)
    private EstadoParticipante estado;

    private LocalDateTime pagadoEn;

    /** La DEVOLUCION con la que pagó. */
    private Long devolucionId;

    /** Si se dio por perdida, el gasto en que se convirtió su parte. */
    private Long gastoPerdidoId;

    protected Participante() {
    }

    Participante(Juntada juntada, int orden, String nombre, BigDecimal monto) {
        this.juntada = juntada;
        this.orden = orden;
        this.nombre = recortar(nombre);
        this.monto = monto;
        this.estado = EstadoParticipante.PENDIENTE;
    }

    static String recortar(String nombre) {
        String limpio = nombre.strip();
        return limpio.length() > LARGO_MAXIMO_NOMBRE ? limpio.substring(0, LARGO_MAXIMO_NOMBRE) : limpio;
    }

    /** Ya no se recalcula: pagó (lo que pagó es lo que debía) o se dio por perdida, o se cambió a mano. */
    boolean isFija() {
        return montoAMano || estado != EstadoParticipante.PENDIENTE;
    }

    void renombrar(String nuevo) {
        this.nombre = recortar(nuevo);
    }

    void cambiarAMano(BigDecimal nuevo) {
        this.monto = nuevo;
        this.montoAMano = true;
    }

    void repartido(BigDecimal nuevo) {
        this.monto = nuevo;
    }

    void pagado(Long devolucionId, LocalDateTime cuando) {
        verificarPendiente();
        this.estado = EstadoParticipante.PAGADO;
        this.devolucionId = devolucionId;
        this.pagadoEn = cuando;
    }

    void perdido(Long gastoId) {
        verificarPendiente();
        this.estado = EstadoParticipante.PERDIDO;
        this.gastoPerdidoId = gastoId;
    }

    private void verificarPendiente() {
        if (estado != EstadoParticipante.PENDIENTE) {
            throw new JuntadaInvalidaException(nombre + " ya no tiene nada pendiente");
        }
    }

    public Long getId() {
        return id;
    }

    public Juntada getJuntada() {
        return juntada;
    }

    public int getOrden() {
        return orden;
    }

    public String getNombre() {
        return nombre;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public boolean isMontoAMano() {
        return montoAMano;
    }

    public EstadoParticipante getEstado() {
        return estado;
    }

    public LocalDateTime getPagadoEn() {
        return pagadoEn;
    }

    public Long getDevolucionId() {
        return devolucionId;
    }

    public Long getGastoPerdidoId() {
        return gastoPerdidoId;
    }
}
