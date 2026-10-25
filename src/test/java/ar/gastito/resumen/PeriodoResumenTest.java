package ar.gastito.resumen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PeriodoResumenTest {

    static final LocalTime DOMINGO_21 = LocalTime.of(21, 0);
    static final LocalTime DIA_1_9 = LocalTime.of(9, 0);

    @ParameterizedTest(name = "{0} está en la semana del {1} al {2}")
    @CsvSource({
            "2026-10-13, 2026-10-12, 2026-10-18", // martes
            "2026-10-12, 2026-10-12, 2026-10-18", // lunes: empieza ahí
            "2026-10-18, 2026-10-12, 2026-10-18", // domingo: termina ahí
            "2026-11-01, 2026-10-26, 2026-11-01", // la semana cruza de mes
            "2027-01-01, 2026-12-28, 2027-01-03"  // y de año
    })
    void laSemanaVaDeLunesADomingo(LocalDate dia, LocalDate lunes, LocalDate domingo) {
        assertThat(PeriodoResumen.semanaDe(dia)).isEqualTo(new PeriodoResumen(TipoResumen.SEMANAL, lunes, domingo));
    }

    @Test
    void elMesVaDelUnoAlUltimoDia() {
        assertThat(PeriodoResumen.mesDe(LocalDate.of(2026, 2, 14)))
                .isEqualTo(new PeriodoResumen(TipoResumen.MENSUAL, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        assertThat(PeriodoResumen.mesDe(LocalDate.of(2028, 2, 14)).hasta()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(PeriodoResumen.mesDe(LocalDate.of(2026, 10, 1)).anterior().desde())
                .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @ParameterizedTest(name = "el {0} el último semanal vencido es el del {1}")
    @CsvSource({
            "2026-10-18T20:59, 2026-10-05", // domingo antes de las 21: todavía no salió el de esta semana
            "2026-10-18T21:00, 2026-10-12", // domingo a las 21: sale el de esta semana
            "2026-10-18T23:59, 2026-10-12",
            "2026-10-19T00:00, 2026-10-12", // lunes: el de la semana que terminó
            "2026-10-22T10:00, 2026-10-12"  // jueves
    })
    void ultimoSemanalVencido(LocalDateTime ahora, LocalDate lunes) {
        assertThat(PeriodoResumen.ultimoVencido(TipoResumen.SEMANAL, ahora, DOMINGO_21, DIA_1_9).desde())
                .isEqualTo(lunes);
    }

    @ParameterizedTest(name = "el {0} el último mensual vencido es {1}")
    @CsvSource({
            "2026-11-01T08:59, 2026-09-01", // día 1 antes de las 9: el de octubre todavía no salió
            "2026-11-01T09:00, 2026-10-01",
            "2026-10-31T23:59, 2026-09-01",
            "2027-01-01T09:00, 2026-12-01"  // cambio de año
    })
    void ultimoMensualVencido(LocalDateTime ahora, LocalDate primero) {
        assertThat(PeriodoResumen.ultimoVencido(TipoResumen.MENSUAL, ahora, DOMINGO_21, DIA_1_9).desde())
                .isEqualTo(primero);
    }

    @Test
    void momentoDeEnvio() {
        assertThat(PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 14)).momentoDeEnvio(DOMINGO_21, DIA_1_9))
                .isEqualTo(LocalDateTime.of(2026, 10, 18, 21, 0));
        assertThat(PeriodoResumen.mesDe(LocalDate.of(2026, 12, 5)).momentoDeEnvio(DOMINGO_21, DIA_1_9))
                .isEqualTo(LocalDateTime.of(2027, 1, 1, 9, 0));
    }

    @Test
    void hastaElMismoPuntoEnElPeriodoAnterior() {
        PeriodoResumen semana = PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 22)); // jueves
        assertThat(semana.mismoPuntoEnAnterior(LocalDate.of(2026, 10, 22))).isEqualTo(LocalDate.of(2026, 10, 15));

        PeriodoResumen octubre = PeriodoResumen.mesDe(LocalDate.of(2026, 10, 31));
        assertThat(octubre.mismoPuntoEnAnterior(LocalDate.of(2026, 10, 13))).isEqualTo(LocalDate.of(2026, 9, 13));
        // El 31 de octubre se compara con el 30 de septiembre, que es su último día.
        assertThat(octubre.mismoPuntoEnAnterior(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 9, 30));
        PeriodoResumen marzo = PeriodoResumen.mesDe(LocalDate.of(2026, 3, 1));
        assertThat(marzo.mismoPuntoEnAnterior(LocalDate.of(2026, 3, 30))).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void unResumenPedidoAntesDelFinEsParcial() {
        PeriodoResumen semana = PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 22));
        assertThat(semana.hastaParaDatos(LocalDate.of(2026, 10, 22))).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(semana.hastaParaDatos(LocalDate.of(2026, 10, 30))).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(semana.diasHasta(LocalDate.of(2026, 10, 22))).isEqualTo(4);
    }
}
