package ar.gastito.catalogo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tarjeta")
public class TarjetaCredito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String banco;

    /** Cuenta desde la que se debita el pago del resumen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_debito_id")
    private Cuenta cuentaDebito;

    private boolean activa = true;

    /** Color de la billetera en el dashboard (#RRGGBB), o null para usar uno de reserva. */
    private String color;

    protected TarjetaCredito() {
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getBanco() {
        return banco;
    }

    public Cuenta getCuentaDebito() {
        return cuentaDebito;
    }

    public boolean isActiva() {
        return activa;
    }

    public String getColor() {
        return color;
    }
}
