package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResolvedorFechasTest {

    /** Martes 13/10/2026 16:42, hora de Argentina. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);

    private final ResolvedorFechas resolvedor = new ResolvedorFechas();

    private LocalDateTime resolver(String fecha, String hora) {
        return resolvedor.resolver(fecha, hora, AHORA);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hoy", "Hoy", "cualquier cosa", "mañana"})
    void sinFechaOConFechaDesconocidaEsAhora(String fecha) {
        assertThat(resolver(fecha, "")).isEqualTo(AHORA);
    }

    @ParameterizedTest
    @CsvSource({
            "ayer, 2026-10-12",
            "Ayer, 2026-10-12",
            "anteayer, 2026-10-11",
            "antes de ayer, 2026-10-11",
            "lunes, 2026-10-12",
            "el sábado, 2026-10-10",
            "sabado, 2026-10-10",
            "domingo, 2026-10-11",
            "miércoles, 2026-10-07",
            "martes, 2026-10-06", // hoy es martes: el martes pasado
            "12/10, 2026-10-12",
            "1/10, 2026-10-01",
            "05-10, 2026-10-05",
            "20/12, 2025-12-20", // todavía no llegó este año: el año pasado
            "12/10/2026, 2026-10-12",
            "12/10/25, 2025-10-12",
            "2026-09-30, 2026-09-30"
    })
    void resuelveFechasRelativasYExplicitas(String fecha, String esperado) {
        assertThat(resolver(fecha, "").toLocalDate()).isEqualTo(esperado);
    }

    @Test
    void otroDiaSinHoraVaAlMediodia() {
        assertThat(resolver("ayer", "")).isEqualTo(LocalDateTime.of(2026, 10, 12, 12, 0));
    }

    @ParameterizedTest
    @CsvSource({"21:30, 21, 30", "9:05, 9, 5", "21, 21, 0", "21hs, 21, 0", "8.15, 8, 15"})
    void usaLaHoraSiLaDice(String hora, int h, int m) {
        assertThat(resolver("ayer", hora)).isEqualTo(LocalDateTime.of(2026, 10, 12, h, m));
    }

    @ParameterizedTest
    @ValueSource(strings = {"25:00", "10:75", "la tarde"})
    void horaInvalidaSeIgnora(String hora) {
        assertThat(resolver("ayer", hora)).isEqualTo(LocalDateTime.of(2026, 10, 12, 12, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20/12/2026", "2027-01-01", "31/02", "32/10"})
    void fechasFuturasOImposiblesSonHoy(String fecha) {
        assertThat(resolver(fecha, "")).isEqualTo(AHORA);
    }
}
