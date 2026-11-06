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

/**
 * El saldo inicial de la bolsa (sin posición) o de una posición, o una compra o venta dentro de la cuenta. No es un
 * movimiento: no pasa plata desde otra cuenta ni cuenta como ingreso o gasto.
 */
@Entity
@Table(name = "operacion_inversion")
public class OperacionInversion {

    static final int LARGO_TEXTO = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posicion_id")
    private PosicionInversion posicion;

    @Enumerated(EnumType.STRING)
    private TipoOperacion tipo;

    private BigDecimal monto;

    private LocalDate fecha;

    private String textoOriginal;

    private LocalDateTime creadaEn;

    protected OperacionInversion() {
    }

    OperacionInversion(Cuenta cuenta, PosicionInversion posicion, TipoOperacion tipo, BigDecimal monto,
            LocalDate fecha, String textoOriginal, LocalDateTime creadaEn) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        if (tipo != TipoOperacion.SALDO_INICIAL && posicion == null) {
            throw new IllegalArgumentException("Una compra o una venta es de una posición");
        }
        if (posicion != null && !posicion.getCuenta().getId().equals(cuenta.getId())) {
            throw new IllegalArgumentException("La posición " + posicion.getNombre() + " no es de " + cuenta.getNombre());
        }
        this.cuenta = cuenta;
        this.posicion = posicion;
        this.tipo = tipo;
        this.monto = monto;
        this.fecha = fecha;
        this.textoOriginal = recortar(textoOriginal);
        this.creadaEn = creadaEn;
    }

    /** El saldo inicial se corrige con uno nuevo: queda uno solo por bolsa o posición. */
    void reemplazar(BigDecimal nuevoMonto, LocalDate nuevaFecha, String texto) {
        if (tipo != TipoOperacion.SALDO_INICIAL) {
            throw new IllegalStateException("Solo se reemplaza un saldo inicial");
        }
        if (nuevoMonto == null || nuevoMonto.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        this.monto = nuevoMonto;
        this.fecha = nuevaFecha;
        this.textoOriginal = recortar(texto);
    }

    private static String recortar(String texto) {
        return texto != null && texto.length() > LARGO_TEXTO ? texto.substring(0, LARGO_TEXTO) : texto;
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

    public TipoOperacion getTipo() {
        return tipo;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public String getTextoOriginal() {
        return textoOriginal;
    }

    public LocalDateTime getCreadaEn() {
        return creadaEn;
    }
}
