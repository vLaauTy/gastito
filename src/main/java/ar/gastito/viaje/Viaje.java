package ar.gastito.viaje;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Un viaje: una etiqueta para los movimientos (la fecha de cada gasto sigue siendo la real), con lo que falta pagar.
 * Entre {@link #desde} y {@link #hasta} (incluidos) está el modo viaje: lo que se carga se asocia solo.
 */
@Entity
@Table(name = "viaje")
public class Viaje {

    static final int LARGO_MAXIMO_NOMBRE = 100;
    static final int LARGO_MAXIMO_NOTAS = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String emoji;

    private LocalDate desde;

    private LocalDate hasta;

    private Integer personas;

    private BigDecimal presupuesto;

    private String notas;

    private LocalDateTime avisoInicioEn;

    private LocalDateTime avisoFinEn;

    private LocalDateTime resumenFinalEn;

    private LocalDateTime creadoEn;

    @OneToMany(mappedBy = "viaje", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PendienteViaje> pendientes = new ArrayList<>();

    protected Viaje() {
    }

    Viaje(String nombre, String emoji, LocalDate desde, LocalDate hasta, Integer personas, BigDecimal presupuesto,
            String notas, LocalDateTime ahora) {
        this.creadoEn = ahora;
        editar(nombre, emoji, desde, hasta, personas, presupuesto, notas);
    }

    void editar(String nombre, String emoji, LocalDate desde, LocalDate hasta, Integer personas,
            BigDecimal presupuesto, String notas) {
        if (nombre == null || nombre.isBlank()) {
            throw new ViajeInvalidoException("Falta el nombre del viaje");
        }
        if (desde == null || hasta == null) {
            throw new ViajeInvalidoException("Faltan las fechas del viaje");
        }
        if (hasta.isBefore(desde)) {
            throw new ViajeInvalidoException("El viaje termina antes de empezar");
        }
        if (personas != null && personas < 1) {
            throw new ViajeInvalidoException("Al menos una persona");
        }
        if (presupuesto != null && presupuesto.signum() <= 0) {
            throw new ViajeInvalidoException("El presupuesto tiene que ser mayor a cero");
        }
        String limpio = nombre.strip();
        this.nombre = limpio.length() > LARGO_MAXIMO_NOMBRE ? limpio.substring(0, LARGO_MAXIMO_NOMBRE) : limpio;
        this.emoji = emoji == null || emoji.isBlank() ? EmojiViaje.para(this.nombre) : emoji.strip();
        // Si cambian las fechas, los avisos del modo viaje se vuelven a mandar.
        if (this.desde != null && !this.desde.equals(desde)) {
            this.avisoInicioEn = null;
        }
        if (this.hasta != null && !this.hasta.equals(hasta)) {
            this.avisoFinEn = null;
            this.resumenFinalEn = null;
        }
        this.desde = desde;
        this.hasta = hasta;
        this.personas = personas;
        this.presupuesto = presupuesto;
        this.notas = notas == null || notas.isBlank() ? null
                : notas.length() > LARGO_MAXIMO_NOTAS ? notas.substring(0, LARGO_MAXIMO_NOTAS) : notas;
    }

    /** El día está dentro del viaje (incluidos el primero y el último). */
    public boolean incluye(LocalDate dia) {
        return !dia.isBefore(desde) && !dia.isAfter(hasta);
    }

    /** Cuántos días dura. */
    public long dias() {
        return ChronoUnit.DAYS.between(desde, hasta) + 1;
    }

    PendienteViaje agregarPendiente(String descripcion, BigDecimal montoAproximado, Long categoriaId,
            LocalDateTime ahora) {
        PendienteViaje p = new PendienteViaje(this, descripcion, montoAproximado, categoriaId, ahora);
        pendientes.add(p);
        return p;
    }

    void avisadoInicio(LocalDateTime cuando) {
        this.avisoInicioEn = cuando;
    }

    void avisadoFin(LocalDateTime cuando) {
        this.avisoFinEn = cuando;
    }

    void resumenFinalEnviado(LocalDateTime cuando) {
        this.resumenFinalEn = cuando;
    }

    /** "🏔 Bariloche". */
    public String getNombreConEmoji() {
        return emoji == null ? nombre : emoji + " " + nombre;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getEmoji() {
        return emoji;
    }

    public LocalDate getDesde() {
        return desde;
    }

    public LocalDate getHasta() {
        return hasta;
    }

    public Integer getPersonas() {
        return personas;
    }

    public BigDecimal getPresupuesto() {
        return presupuesto;
    }

    public String getNotas() {
        return notas;
    }

    public LocalDateTime getAvisoInicioEn() {
        return avisoInicioEn;
    }

    public LocalDateTime getAvisoFinEn() {
        return avisoFinEn;
    }

    public LocalDateTime getResumenFinalEn() {
        return resumenFinalEn;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public List<PendienteViaje> getPendientes() {
        return pendientes;
    }
}
