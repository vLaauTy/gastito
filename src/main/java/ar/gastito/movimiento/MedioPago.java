package ar.gastito.movimiento;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "medio_pago")
public class MedioPago implements ConPalabrasClave {

    public static final String TARJETA_DE_CREDITO = "Tarjeta de crédito";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String palabrasClave;

    private boolean activo = true;

    protected MedioPago() {
    }

    public MedioPago(String nombre, String palabrasClave) {
        this.nombre = nombre;
        this.palabrasClave = palabrasClave;
    }

    public Long getId() {
        return id;
    }

    @Override
    public String getNombre() {
        return nombre;
    }

    @Override
    public String getPalabrasClave() {
        return palabrasClave;
    }

    public boolean isActivo() {
        return activo;
    }

    public boolean esTarjetaDeCredito() {
        return TARJETA_DE_CREDITO.equals(nombre);
    }
}
