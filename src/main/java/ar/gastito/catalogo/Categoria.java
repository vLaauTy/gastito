package ar.gastito.catalogo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categoria")
public class Categoria implements ConPalabrasClave {

    public static final String OTROS = "Otros";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String palabrasClave;

    /** Qué entra en esta categoría. Se le pasa a la IA. */
    private String descripcion;

    private String emoji;

    private boolean activa = true;

    protected Categoria() {
    }

    public Categoria(String nombre, String palabrasClave) {
        this.nombre = nombre;
        this.palabrasClave = palabrasClave;
    }

    public Categoria(Long id, String nombre, String palabrasClave, String descripcion, String emoji) {
        this(nombre, palabrasClave);
        this.id = id;
        this.descripcion = descripcion;
        this.emoji = emoji;
    }

    public Long getId() {
        return id;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getEmoji() {
        return emoji;
    }

    /** "🍬 Kiosco", o solo el nombre si no tiene emoji. */
    public String getNombreConEmoji() {
        return emoji == null || emoji.isBlank() ? nombre : emoji + " " + nombre;
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
