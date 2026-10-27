package ar.gastito.fijo;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cuándo se mandó el último recordatorio agrupado de fijos vencidos sin cargar (una sola fila en la tabla). */
@Entity
@Table(name = "recordatorio_fijo")
public class RecordatorioFijo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime enviadoEn;

    protected RecordatorioFijo() {
    }

    public RecordatorioFijo(LocalDateTime enviadoEn) {
        this.enviadoEn = enviadoEn;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getEnviadoEn() {
        return enviadoEn;
    }
}
