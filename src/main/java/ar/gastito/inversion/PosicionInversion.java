package ar.gastito.inversion;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.catalogo.Cuenta;

/** Una posición con nombre libre dentro de una cuenta de inversión ("CEDEAR SPY", "FCI money market"). */
@Entity
@Table(name = "posicion_inversion")
public class PosicionInversion implements ConPalabrasClave {

    static final int LARGO_NOMBRE = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id")
    private Cuenta cuenta;

    private String nombre;

    private String palabrasClave;

    private boolean activa = true;

    private LocalDateTime creadaEn;

    protected PosicionInversion() {
    }

    public PosicionInversion(Cuenta cuenta, String nombre, String palabrasClave, LocalDateTime creadaEn) {
        if (cuenta == null || !cuenta.isInversion()) {
            throw new IllegalArgumentException("Una posición va dentro de una cuenta de inversión");
        }
        this.cuenta = cuenta;
        this.creadaEn = creadaEn;
        editar(nombre, palabrasClave);
    }

    public void editar(String nombre, String palabrasClave) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Poné el nombre de la posición");
        }
        if (nombre.strip().length() > LARGO_NOMBRE) {
            throw new IllegalArgumentException("El nombre de la posición puede tener hasta " + LARGO_NOMBRE + " letras");
        }
        this.nombre = nombre.strip();
        this.palabrasClave = palabrasClave == null || palabrasClave.isBlank() ? null : palabrasClave.strip();
    }

    public Long getId() {
        return id;
    }

    public Cuenta getCuenta() {
        return cuenta;
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

    public void darDeBaja() {
        this.activa = false;
    }

    public LocalDateTime getCreadaEn() {
        return creadaEn;
    }
}
