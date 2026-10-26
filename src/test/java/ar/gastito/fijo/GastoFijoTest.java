package ar.gastito.fijo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class GastoFijoTest {

    private static GastoFijo fijo(int dia, Frecuencia frecuencia, YearMonth inicio) {
        return new GastoFijo("Seguro", new BigDecimal("32900"), null, null, null, null, dia, frecuencia, inicio, false,
                LocalDateTime.now());
    }

    @Test
    void unMensualSeCobraTodosLosMesesDesdeElDeInicio() {
        GastoFijo f = fijo(4, Frecuencia.MENSUAL, YearMonth.of(2026, 9));

        assertThat(f.seCobraEn(YearMonth.of(2026, 8))).isFalse();
        assertThat(f.seCobraEn(YearMonth.of(2026, 9))).isTrue();
        assertThat(f.seCobraEn(YearMonth.of(2026, 10))).isTrue();
        assertThat(f.seCobraEn(YearMonth.of(2027, 1))).isTrue();
    }

    @Test
    void unBimestralCadaDosMesesDesdeElDeInicio() {
        GastoFijo f = fijo(15, Frecuencia.BIMESTRAL, YearMonth.of(2026, 9));

        assertThat(f.seCobraEn(YearMonth.of(2026, 9))).isTrue();
        assertThat(f.seCobraEn(YearMonth.of(2026, 10))).isFalse();
        assertThat(f.seCobraEn(YearMonth.of(2026, 11))).isTrue();
        assertThat(f.seCobraEn(YearMonth.of(2027, 1))).isTrue();
        assertThat(f.seCobraEn(YearMonth.of(2027, 2))).isFalse();
    }

    @Test
    void siElMesNoTieneEseDiaSeCobraElUltimo() {
        GastoFijo f = fijo(31, Frecuencia.MENSUAL, YearMonth.of(2026, 1));

        assertThat(f.diaDeCobro(YearMonth.of(2026, 2))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(f.diaDeCobro(YearMonth.of(2028, 2))).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(f.diaDeCobro(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(f.diaDeCobro(YearMonth.of(2026, 5))).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    void elProximoCobro() {
        GastoFijo mensual = fijo(4, Frecuencia.MENSUAL, YearMonth.of(2026, 9));
        GastoFijo bimestral = fijo(15, Frecuencia.BIMESTRAL, YearMonth.of(2026, 9));

        assertThat(mensual.proximoCobro(LocalDate.of(2026, 10, 4))).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(mensual.proximoCobro(LocalDate.of(2026, 10, 25))).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(bimestral.proximoCobro(LocalDate.of(2026, 9, 20))).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(mensual.proximoCobro(LocalDate.of(2026, 8, 1))).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    void elPrimerMesEsEsteSiTodaviaNoPasoElDia() {
        assertThat(GastoFijoService.primerMes(28, LocalDate.of(2026, 10, 25))).isEqualTo(YearMonth.of(2026, 10));
        assertThat(GastoFijoService.primerMes(25, LocalDate.of(2026, 10, 25))).isEqualTo(YearMonth.of(2026, 10));
        assertThat(GastoFijoService.primerMes(4, LocalDate.of(2026, 10, 25))).isEqualTo(YearMonth.of(2026, 11));
        // El 31 en febrero es el 28: el 28 de febrero todavía es este mes.
        assertThat(GastoFijoService.primerMes(31, LocalDate.of(2026, 2, 28))).isEqualTo(YearMonth.of(2026, 2));
    }

    @Test
    void elAvisoDeAumentoEsDeMasDel5PorCiento() {
        assertThat(GastoFijoService.aumento("Seguro", new BigDecimal("32900"), new BigDecimal("36100"))).get()
                .satisfies(a -> {
                    assertThat(a.porcentaje()).isEqualByComparingTo("9.7");
                    assertThat(a.subio()).isTrue();
                });
        assertThat(GastoFijoService.aumento("Seguro", new BigDecimal("32900"), new BigDecimal("34500"))).isEmpty();
        assertThat(GastoFijoService.aumento("Luz", new BigDecimal("30000"), new BigDecimal("27000"))).get()
                .satisfies(a -> assertThat(a.porcentaje()).isEqualByComparingTo("-10.0"));
        assertThat(GastoFijoService.aumento("Luz", null, new BigDecimal("27000"))).isEmpty();
    }

    @Test
    void elDiaTieneQueSerDel1Al31() {
        assertThatThrownBy(() -> fijo(32, Frecuencia.MENSUAL, YearMonth.of(2026, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fijo(0, Frecuencia.MENSUAL, YearMonth.of(2026, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
