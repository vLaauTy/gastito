package ar.gastito.inversion;

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

import ar.gastito.catalogo.Cuenta;

/** Cuánto valía la bolsa de una cuenta (sin posición) o una posición en una fecha. No es un movimiento. */
@Entity
@Table(name = "valuacion_inversion")
public class ValuacionInversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posicion_id")
    private PosicionInversion posicion;

    private LocalDate fecha;

    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    private OrigenValuacion origen;

    /** En un ajuste: el aporte o retiro que lo movió. */
    private Long movimientoId;

    /** En un ajuste: la compra o venta que lo movió. */
    private Long operacionId;

    private LocalDateTime creadaEn;

    protected ValuacionInversion() {
    }

    ValuacionInversion(Cuenta cuenta, PosicionInversion posicion, LocalDate fecha, BigDecimal valor,
            OrigenValuacion origen, Long movimientoId, Long operacionId, LocalDateTime creadaEn) {
        if (valor == null || valor.signum() < 0) {
            throw new IllegalArgumentException("El valor no puede ser negativo");
        }
        this.cuenta = cuenta;
        this.posicion = posicion;
        this.fecha = fecha;
        this.valor = valor;
        this.origen = origen;
        this.movimientoId = movimientoId;
        this.operacionId = operacionId;
        this.creadaEn = creadaEn;
    }

    public Long getId() {
        return id;
    }

    public Cuenta getCuenta() {
        return cuenta;
    }

    public PosicionInversion getPosicion() {
        return posicion;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public OrigenValuacion getOrigen() {
        return origen;
    }

    public Long getMovimientoId() {
        return movimientoId;
    }

    public Long getOperacionId() {
        return operacionId;
    }

    public LocalDateTime getCreadaEn() {
        return creadaEn;
    }
}
