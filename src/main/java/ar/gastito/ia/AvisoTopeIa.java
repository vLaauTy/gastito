package ar.gastito.ia;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un aviso del tope mensual que ya se mandó: uno por mes y por umbral (80 o 100 por ciento). */
@Entity
@Table(name = "aviso_tope_ia")
public class AvisoTopeIa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** El día 1 del mes. */
    private LocalDate mes;

    private int umbral;

    private LocalDateTime enviadoEn;

    protected AvisoTopeIa() {
    }

    AvisoTopeIa(LocalDate mes, int umbral, LocalDateTime enviadoEn) {
        this.mes = mes;
        this.umbral = umbral;
        this.enviadoEn = enviadoEn;
    }

    public LocalDate getMes() {
        return mes;
    }

    public int getUmbral() {
        return umbral;
    }

    public LocalDateTime getEnviadoEn() {
        return enviadoEn;
    }
}
