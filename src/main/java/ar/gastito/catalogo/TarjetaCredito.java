package ar.gastito.catalogo;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Una tarjeta de crédito (MasterCard Gold, Mercado Pago…): cada compra con crédito es de una, y cada una tiene su
 * ciclo (resúmenes, cierre y vencimiento) y se paga desde su cuenta. Dar de baja no borra el historial.
 */
@Entity
@Table(name = "tarjeta")
public class TarjetaCredito implements ConPalabrasClave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    /** El emisor (Galicia, Mercado Pago). */
    private String banco;

    /** Cuenta desde la que se debita el pago del resumen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_debito_id")
    private Cuenta cuentaDebito;

    private boolean activa = true;

    /** Color de la billetera en el dashboard (#RRGGBB), o null para usar uno de reserva. */
    private String color;

    /** La letra que la representa en la web, o null para usar la primera del nombre. */
    private String inicial;

    /** Cómo se la nombra ("master,la de galicia"), sin tildes y separadas por coma. */
    private String palabrasClave;

    /** Sin ningún cierre cargado: el día que se preguntaron las fechas y hasta cuándo se pospuso la pregunta. */
    private LocalDate preguntaFechasEn;
    private LocalDate fechasPospuestasHasta;

    protected TarjetaCredito() {
    }

    public TarjetaCredito(String nombre, String banco, Cuenta cuentaDebito, String color, String inicial,
            String palabrasClave) {
        editar(nombre, banco, cuentaDebito, color, inicial, palabrasClave);
    }

    public void editar(String nombre, String banco, Cuenta cuentaDebito, String color, String inicial,
            String palabrasClave) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("La tarjeta necesita un nombre");
        }
        if (color != null && !color.isBlank() && !color.matches("#[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("El color tiene que ser #RRGGBB");
        }
        this.nombre = nombre.strip();
        this.banco = vacioANull(banco);
        this.cuentaDebito = cuentaDebito;
        this.color = vacioANull(color);
        String letra = vacioANull(inicial);
        this.inicial = letra == null ? null : letra.substring(0, Math.min(2, letra.length())).toUpperCase();
        this.palabrasClave = normalizarPalabras(palabrasClave);
    }

    public void darDeBaja() {
        this.activa = false;
    }

    public void reactivar() {
        this.activa = true;
    }

    /** "master, La de Galicia ,," → "master,la de galicia". */
    static String normalizarPalabras(String palabras) {
        if (palabras == null) {
            return null;
        }
        String unidas = String.join(",", Arrays.stream(palabras.split(","))
                .map(p -> java.text.Normalizer.normalize(p.strip().toLowerCase(java.util.Locale.ROOT),
                        java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("\\s+", " "))
                .filter(p -> !p.isEmpty()).distinct().toList());
        return unidas.isEmpty() ? null : unidas;
    }

    private static String vacioANull(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    public void preguntoFechas(LocalDate dia) {
        this.preguntaFechasEn = dia;
    }

    public void posponerFechas(LocalDate hasta) {
        this.fechasPospuestasHasta = hasta;
    }

    public Long getId() {
        return id;
    }

    @Override
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

    /** La letra para la web: la cargada, o la primera del nombre. */
    public String getInicial() {
        return inicial != null ? inicial : nombre.substring(0, 1).toUpperCase();
    }

    @Override
    public String getPalabrasClave() {
        return palabrasClave;
    }

    public List<String> alias() {
        return palabrasClave == null ? List.of() : List.of(palabrasClave.split(","));
    }

    public LocalDate getPreguntaFechasEn() {
        return preguntaFechasEn;
    }

    public LocalDate getFechasPospuestasHasta() {
        return fechasPospuestasHasta;
    }
}
