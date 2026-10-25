package ar.gastito.movimiento;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Un renglón de un ticket: un producto, o un descuento (con subtotal negativo). */
@Entity
@Table(name = "movimiento_item")
public class MovimientoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movimiento_id")
    private Movimiento movimiento;

    /** Desde 0, en el orden impreso. */
    private int orden;

    private String descripcion;

    /** Como está impresa en el ticket, o null. */
    private String descripcionOriginal;

    /** EAN-8 o EAN-13 validado, o null. */
    private String codigoBarras;

    /** Null si el ticket no la dice (se entiende 1). */
    private BigDecimal cantidad;

    private BigDecimal precioUnitario;

    private BigDecimal subtotal;

    protected MovimientoItem() {
    }

    MovimientoItem(Movimiento movimiento, int orden, ItemTicket item) {
        this.movimiento = movimiento;
        this.orden = orden;
        this.descripcion = item.descripcion();
        this.descripcionOriginal = item.descripcionOriginal();
        this.codigoBarras = item.codigoBarras();
        this.cantidad = item.cantidad();
        this.precioUnitario = item.precioUnitario();
        this.subtotal = item.subtotal();
    }

    public boolean isDescuento() {
        return subtotal.signum() < 0;
    }

    public Long getId() {
        return id;
    }

    public int getOrden() {
        return orden;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getDescripcionOriginal() {
        return descripcionOriginal;
    }

    public String getCodigoBarras() {
        return codigoBarras;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
