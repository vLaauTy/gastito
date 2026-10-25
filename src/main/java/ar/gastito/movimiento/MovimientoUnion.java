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

/** Dos movimientos que eran la misma compra se unieron: queda uno, y acá lo que aportó el otro. */
@Entity
@Table(name = "movimiento_union")
public class MovimientoUnion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** El que quedó. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movimiento_id")
    private Movimiento movimiento;

    /** De dónde vino el que se unió. */
    @Enumerated(EnumType.STRING)
    private Origen origen;

    private BigDecimal monto;

    private LocalDateTime fechaHora;

    private String textoOriginal;

    private LocalDateTime creadoEn;

    protected MovimientoUnion() {
    }

    MovimientoUnion(Movimiento queda, Origen origen, BigDecimal monto, LocalDateTime fechaHora, String textoOriginal,
            LocalDateTime ahora) {
        this.movimiento = queda;
        this.origen = origen;
        this.monto = monto;
        this.fechaHora = fechaHora;
        this.textoOriginal = textoOriginal;
        this.creadoEn = ahora;
    }

    public Long getId() {
        return id;
    }

    public Movimiento getMovimiento() {
        return movimiento;
    }

    public Origen getOrigen() {
        return origen;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public String getTextoOriginal() {
        return textoOriginal;
    }
}
