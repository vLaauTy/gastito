package ar.gastito.ia;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Una llamada a la API de Anthropic, con sus tokens y su costo. */
@Entity
@Table(name = "uso_ia")
public class UsoIa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime fechaHora;

    private String modelo;

    private long tokensEntrada;

    private long tokensSalida;

    @Enumerated(EnumType.STRING)
    private PropositoIa proposito;

    private BigDecimal costoUsd;

    /** Movimiento que se generó a partir de esta llamada, si hubo uno. */
    private Long movimientoId;

    protected UsoIa() {
    }

    public UsoIa(LocalDateTime fechaHora, String modelo, long tokensEntrada, long tokensSalida,
            PropositoIa proposito, BigDecimal costoUsd) {
        this.fechaHora = fechaHora;
        this.modelo = modelo;
        this.tokensEntrada = tokensEntrada;
        this.tokensSalida = tokensSalida;
        this.proposito = proposito;
        this.costoUsd = costoUsd;
    }

    public void asociarMovimiento(Long movimientoId) {
        this.movimientoId = movimientoId;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public String getModelo() {
        return modelo;
    }

    public long getTokensEntrada() {
        return tokensEntrada;
    }

    public long getTokensSalida() {
        return tokensSalida;
    }

    public PropositoIa getProposito() {
        return proposito;
    }

    public BigDecimal getCostoUsd() {
        return costoUsd;
    }

    public Long getMovimientoId() {
        return movimientoId;
    }
}
