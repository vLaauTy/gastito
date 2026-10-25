package ar.gastito.resumen;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un resumen que se armó: los datos que calculó Java (en JSON) y lo que redactó la IA a partir de ellos. */
@Entity
@Table(name = "resumen")
public class Resumen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private TipoResumen tipo;

    private LocalDate desde;

    /** Último día incluido. En un resumen parcial, anterior al fin del período. */
    private LocalDate hasta;

    private boolean parcial;

    private boolean automatico;

    private String datos;

    /** Una por renglón. */
    private String observaciones;

    private String consejo;

    private boolean textoIaDescartado;

    private Long usoIaId;

    private LocalDateTime creadoEn;

    private LocalDateTime enviadoEn;

    protected Resumen() {
    }

    /**
     * @param datos         el JSON con los números calculados
     * @param observaciones de la IA, ya controladas; vacía si no hay texto de la IA
     * @param consejo       de la IA, ya controlado; o null
     */
    public Resumen(TipoResumen tipo, LocalDate desde, LocalDate hasta, boolean parcial, boolean automatico,
            String datos, List<String> observaciones, String consejo, boolean textoIaDescartado, Long usoIaId,
            LocalDateTime creadoEn) {
        this.tipo = tipo;
        this.desde = desde;
        this.hasta = hasta;
        this.parcial = parcial;
        this.automatico = automatico;
        this.datos = datos;
        this.observaciones = observaciones == null || observaciones.isEmpty() ? null : String.join("\n", observaciones);
        this.consejo = consejo;
        this.textoIaDescartado = textoIaDescartado;
        this.usoIaId = usoIaId;
        this.creadoEn = creadoEn;
    }

    public void marcarEnviado(LocalDateTime cuando) {
        this.enviadoEn = cuando;
    }

    public Long getId() {
        return id;
    }

    public TipoResumen getTipo() {
        return tipo;
    }

    public LocalDate getDesde() {
        return desde;
    }

    public LocalDate getHasta() {
        return hasta;
    }

    public boolean isParcial() {
        return parcial;
    }

    public boolean isAutomatico() {
        return automatico;
    }

    public String getDatos() {
        return datos;
    }

    public List<String> getObservaciones() {
        return observaciones == null ? List.of() : List.of(observaciones.split("\n"));
    }

    public String getConsejo() {
        return consejo;
    }

    public boolean isTextoIaDescartado() {
        return textoIaDescartado;
    }

    public Long getUsoIaId() {
        return usoIaId;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public LocalDateTime getEnviadoEn() {
        return enviadoEn;
    }
}
