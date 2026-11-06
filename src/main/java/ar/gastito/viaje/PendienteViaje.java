package ar.gastito.viaje;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Algo que falta pagar del viaje (el hospedaje, los traslados…), con un monto aproximado opcional. Al pagarlo se
 * crea el gasto del viaje; queda pagado mientras ese gasto esté guardado.
 */
@Entity
@Table(name = "viaje_pendiente")
public class PendienteViaje {

    static final int LARGO_MAXIMO_DESCRIPCION = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "viaje_id")
    private Viaje viaje;

    private String descripcion;

    private BigDecimal montoAproximado;

    private Long categoriaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movimiento_id")
    private Movimiento movimiento;

    private LocalDateTime pagadoEn;

    private LocalDateTime creadoEn;

    protected PendienteViaje() {
    }

    PendienteViaje(Viaje viaje, String descripcion, BigDecimal montoAproximado, Long categoriaId,
            LocalDateTime ahora) {
        this.viaje = viaje;
        this.creadoEn = ahora;
        editar(descripcion, montoAproximado, categoriaId);
    }

    void editar(String descripcion, BigDecimal montoAproximado, Long categoriaId) {
        if (descripcion == null || descripcion.isBlank()) {
            throw new ViajeInvalidoException("Falta qué es lo que falta pagar");
        }
        if (montoAproximado != null && montoAproximado.signum() <= 0) {
            throw new ViajeInvalidoException("El monto tiene que ser mayor a cero");
        }
        String limpia = descripcion.strip();
        limpia = Character.toUpperCase(limpia.charAt(0)) + limpia.substring(1);
        this.descripcion = limpia.length() > LARGO_MAXIMO_DESCRIPCION ? limpia.substring(0, LARGO_MAXIMO_DESCRIPCION)
                : limpia;
        this.montoAproximado = montoAproximado;
        this.categoriaId = categoriaId;
    }

    /** Ya está pagado: su gasto está guardado. */
    public boolean isPagado() {
        return movimiento != null && movimiento.getEstado() == EstadoMovimiento.CONFIRMADO;
    }

    void pagarCon(Movimiento gasto, LocalDateTime cuando) {
        this.movimiento = gasto;
        this.pagadoEn = cuando;
    }

    void desmarcar() {
        this.movimiento = null;
        this.pagadoEn = null;
    }

    public Long getId() {
        return id;
    }

    public Viaje getViaje() {
        return viaje;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getMontoAproximado() {
        return montoAproximado;
    }

    public Long getCategoriaId() {
        return categoriaId;
    }

    public Movimiento getMovimiento() {
        return movimiento;
    }

    public LocalDateTime getPagadoEn() {
        return pagadoEn;
    }
}
