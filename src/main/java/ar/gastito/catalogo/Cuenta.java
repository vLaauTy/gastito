package ar.gastito.catalogo;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Color de la billetera en el dashboard (#RRGGBB), o null para usar uno de reserva. */
    private String color;

    /** Bancaria (o billetera: con ella se pagan gastos) o de inversión (ahorros: no se usa para pagar). */
    @Enumerated(EnumType.STRING)
    private TipoCuenta tipo = TipoCuenta.BANCARIA;

    /** Solo en una de inversión: cada cuántos días se pregunta cuánto vale; null si no se pregunta. */
    private Integer recordatorioDias;

    /** Cuándo se preguntó por última vez cuánto vale. */
    private LocalDateTime recordatorioEnviadoEn;

    protected Cuenta() {
    }

    public Cuenta(String nombre, String palabrasClave) {
        this.nombre = nombre;
        this.palabrasClave = palabrasClave;
    }

    /** Una cuenta de inversión nueva ("Cocos"), con el recordatorio de cada cuántos días (o null, sin recordatorio). */
    public static Cuenta deInversion(String nombre, String palabrasClave, Integer recordatorioDias) {
        Cuenta c = new Cuenta(null, null);
        c.tipo = TipoCuenta.INVERSION;
        c.editar(nombre, palabrasClave);
        c.cambiarRecordatorio(recordatorioDias);
        return c;
    }

    public boolean isInversion() {
        return tipo == TipoCuenta.INVERSION;
    }

    public TipoCuenta getTipo() {
        return tipo;
    }

    public Integer getRecordatorioDias() {
        return recordatorioDias;
    }

    public LocalDateTime getRecordatorioEnviadoEn() {
        return recordatorioEnviadoEn;
    }

    /** Cada cuántos días preguntar cuánto vale (null o 0: no preguntar). Solo en una de inversión. */
    public void cambiarRecordatorio(Integer dias) {
        if (!isInversion()) {
            throw new IllegalStateException(nombre + " no es una cuenta de inversión");
        }
        if (dias != null && (dias < 0 || dias > 365)) {
            throw new IllegalArgumentException("El recordatorio va de 1 a 365 días (o ninguno)");
        }
        this.recordatorioDias = dias == null || dias == 0 ? null : dias;
    }

    public void recordatorioEnviado(LocalDateTime cuando) {
        this.recordatorioEnviadoEn = cuando;
    }

    /** El nombre (hasta 50 letras) y las palabras con las que se la nombra, separadas por coma. */
    public void editar(String nombre, String palabrasClave) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Poné el nombre de la cuenta");
        }
        if (nombre.strip().length() > 50) {
            throw new IllegalArgumentException("El nombre de la cuenta puede tener hasta 50 letras");
        }
        this.nombre = nombre.strip();
        this.palabrasClave = palabrasClave == null || palabrasClave.isBlank() ? null : palabrasClave.strip();
    }

    public void darDeBaja() {
        this.activa = false;
    }

    public void reactivar() {
        this.activa = true;
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

    public String getColor() {
        return color;
    }
}
