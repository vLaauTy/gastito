package ar.gastito.resumen;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Qué pasó con el envío automático de un período (ver la migración V6). */
@Entity
@Table(name = "resumen_envio")
public class EnvioResumen {

    public enum Estado {
        ENVIADO,
        /** La primera vez que corre la aplicación: no se manda historia. */
        OMITIDO,
        /** Había varios pendientes: quedó en "Ver anteriores". */
        OFRECIDO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private TipoResumen tipo;

    private LocalDate desde;

    private LocalDate hasta;

    @Enumerated(EnumType.STRING)
    private Estado estado;

    private Long resumenId;

    private LocalDateTime actualizado;

    protected EnvioResumen() {
    }

    public EnvioResumen(PeriodoResumen periodo, Estado estado, Long resumenId, LocalDateTime ahora) {
        this.tipo = periodo.tipo();
        this.desde = periodo.desde();
        this.hasta = periodo.hasta();
        this.estado = estado;
        this.resumenId = resumenId;
        this.actualizado = ahora;
    }

    public void enviado(Long resumen, LocalDateTime ahora) {
        this.estado = Estado.ENVIADO;
        this.resumenId = resumen;
        this.actualizado = ahora;
    }

    public PeriodoResumen periodo() {
        return new PeriodoResumen(tipo, desde, hasta);
    }

    public TipoResumen getTipo() {
        return tipo;
    }

    public LocalDate getDesde() {
        return desde;
    }

    public Estado getEstado() {
        return estado;
    }

    public Long getResumenId() {
        return resumenId;
    }
}
