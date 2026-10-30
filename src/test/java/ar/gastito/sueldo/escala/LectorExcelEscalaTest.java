package ar.gastito.sueldo.escala;

import static ar.gastito.sueldo.escala.EscalaDePrueba.excelDeDosColumnas;
import static ar.gastito.sueldo.escala.EscalaDePrueba.excelDeLaHoja;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class LectorExcelEscalaTest {

    private final LectorExcelEscala lector = new LectorExcelEscala();

    private static HojaEscala.Fila fila(HojaEscala.Columna c, String nombre) {
        return c.filas().stream().filter(f -> f.nombre().equals(nombre)).findFirst().orElse(null);
    }

    @Test
    void leeUnaColumnaPorMesConElMesDelEncabezado() {
        HojaEscala hoja = lector.leer(excelDeLaHoja(null));

        assertThat(hoja.columnas()).extracting(HojaEscala.Columna::mes)
                .containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11));
        HojaEscala.Columna octubre = hoja.columnas().getFirst();
        assertThat(octubre.filas()).hasSize(EscalaDePrueba.FILAS.size());
        // Octubre viene como número de Excel y noviembre como texto con formato de plata: se leen igual.
        assertThat(fila(octubre, "BASICO").valor()).isEqualByComparingTo("500000");
        assertThat(fila(octubre, "HORAS AL 50 %").valor()).isEqualByComparingTo("4875.10");
        assertThat(fila(hoja.columnas().get(1), "BASICO").valor()).isEqualByComparingTo("545000");
        assertThat(fila(hoja.columnas().get(1), "VALORES NETOS").valor()).isEqualByComparingTo("839800");
    }

    @Test
    void lasCeldasConFormulasSeIgnoran() {
        HojaEscala hoja = lector.leer(excelDeLaHoja("VALORES NETOS"));

        assertThat(fila(hoja.columnas().getFirst(), "VALORES NETOS")).isNull();
        assertThat(fila(hoja.columnas().get(1), "VALORES NETOS")).isNotNull();
    }

    @Test
    void laPlantillaDeDosColumnasTomaElMesDeLaFilaMes() {
        HojaEscala hoja = lector.leer(excelDeDosColumnas("oct-26"));

        assertThat(hoja.faltaElMes()).isFalse();
        assertThat(hoja.columnas()).singleElement().satisfies(c -> {
            assertThat(c.mes()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(fila(c, "PRESENTISMO").valor()).isEqualByComparingTo("100000");
        });
    }

    @Test
    void laPlantillaDeDosColumnasSinMesQuedaSinMes() {
        HojaEscala hoja = lector.leer(excelDeDosColumnas(null));

        assertThat(hoja.faltaElMes()).isTrue();
        assertThat(hoja.conMes(YearMonth.of(2026, 12)).columnas().getFirst().mes()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(fila(hoja.columnas().getFirst(), "A.I.S.").valor()).isEqualByComparingTo("50000");
    }

    @Test
    void unArchivoQueNoEsExcelSeRechaza() {
        assertThatThrownBy(() -> lector.leer("no soy un excel".getBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Excel");
    }
}
