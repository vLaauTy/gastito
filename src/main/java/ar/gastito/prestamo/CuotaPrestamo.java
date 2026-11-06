package ar.gastito.prestamo;

import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Una cuota de un préstamo: cuándo me la tiene que pagar, cuánto y si ya me la pagó. */
@Entity
@Table(name = "prestamo_cuota")
public class CuotaPrestamo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prestamo_id")
    private Prestamo prestamo;

    private int numero;

    private LocalDate vence;

    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    private EstadoCuota estado;

    private LocalDateTime cobradaEn;

    private Long devolucionId;

    private LocalDateTime avisoEnviadoEn;

    protected CuotaPrestamo() {
    }

    CuotaPrestamo(Prestamo prestamo, int numero, LocalDate vence, BigDecimal monto) {
        this.prestamo = prestamo;
        this.numero = numero;
        this.vence = vence;
        this.monto = monto;
        this.estado = EstadoCuota.PENDIENTE;
    }

    void cobrar(BigDecimal cobrado, Long devolucionId, LocalDateTime cuando) {
        this.monto = cobrado;
        this.estado = EstadoCuota.COBRADA;
        this.devolucionId = devolucionId;
        this.cobradaEn = cuando;
    }

    void cambiarMonto(BigDecimal nuevo) {
        this.monto = nuevo;
    }

    /** Pasó la fecha (con unos días de gracia) y no me la pagó. */
    void atrasar() {
        if (estado == EstadoCuota.PENDIENTE) {
            this.estado = EstadoCuota.ATRASADA;
        }
    }

    void avisada(LocalDateTime cuando) {
        this.avisoEnviadoEn = cuando;
    }

    public Long getId() {
        return id;
    }

    public Prestamo getPrestamo() {
        return prestamo;
    }

    public int getNumero() {
        return numero;
    }

    public LocalDate getVence() {
        return vence;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public EstadoCuota getEstado() {
        return estado;
    }

    public LocalDateTime getCobradaEn() {
        return cobradaEn;
    }

    public Long getDevolucionId() {
        return devolucionId;
    }

    public LocalDateTime getAvisoEnviadoEn() {
        return avisoEnviadoEn;
    }
}
