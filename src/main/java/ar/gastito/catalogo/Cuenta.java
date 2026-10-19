package ar.gastito.catalogo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cuenta")
public class Cuenta implements ConPalabrasClave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String palabrasClave;

    private boolean activa = true;

    protected Cuenta() {
    }

    public Cuenta(String nombre, String palabrasClave) {
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

    public boolean isActiva() {
        return activa;
    }
}
