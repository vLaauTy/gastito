package ar.gastito.inversion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ar.gastito.catalogo.Cuenta;

/** Parte del valor de una cuenta de inversión apartada para una meta ("300.000 de Cocos para Bariloche"). */
@Entity
@Table(name = "reserva_inversion")
public class ReservaInversion {

    static final int LARGO_META = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    private String meta;

    private BigDecimal monto;

    private LocalDateTime creadaEn;

    /** Cuándo dejó de estar reservada (se usó o se liberó), o null si sigue. */
    private LocalDateTime liberadaEn;

    protected ReservaInversion() {
    }

    ReservaInversion(Cuenta cuenta, String meta, BigDecimal monto, LocalDateTime creadaEn) {
        if (meta == null || meta.isBlank()) {
            throw new IllegalArgumentException("Decime para qué es la reserva");
        }
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de la reserva tiene que ser mayor a cero");
        }
        String limpia = meta.strip();
        this.cuenta = cuenta;
        this.meta = limpia.length() > LARGO_META ? limpia.substring(0, LARGO_META) : limpia;
        this.monto = monto;
        this.creadaEn = creadaEn;
    }

    void liberar(LocalDateTime cuando) {
        if (liberadaEn == null) {
            liberadaEn = cuando;
        }
    }

    public boolean isActiva() {
        return liberadaEn == null;
    }

    public Long getId() {
        return id;
    }

    public Cuenta getCuenta() {
        return cuenta;
    }

    public String getMeta() {
        return meta;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public LocalDateTime getCreadaEn() {
        return creadaEn;
    }

    public LocalDateTime getLiberadaEn() {
        return liberadaEn;
    }
}
