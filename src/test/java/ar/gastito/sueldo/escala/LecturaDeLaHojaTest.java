package ar.gastito.sueldo.escala;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

/** Los nombres de fila, los importes y los meses como vienen en la hoja del sindicato. */
class LecturaDeLaHojaTest {

    @Test
    void elNombreDeLaFilaSeComparaSinMayusculasAcentosEspaciosNiPuntos() {
        assertThat(NombreFila.clave("A.I.S.")).isEqualTo(NombreFila.clave("AIS")).isEqualTo("ais");
        assertThat(NombreFila.clave("HORAS AL 50 %")).isEqualTo(NombreFila.clave("Horas al 50%"));
        assertThat(NombreFila.clave("Viáticos")).isEqualTo("viaticos");
        // La fila del 6% y la otra son distintas.
        assertThat(NombreFila.clave("ASIG. EXT. NO REMUNERATIVA 6%"))
                .isNotEqualTo(NombreFila.clave("ASIG. EXT. NO REMUNERATIVA"));
    }

    @Test
    void losImportesSeLeenEnFormatoArgentino() {
        assertThat(ImporteHoja.leer("$ 1.023.863,86")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1023863.86"));
        assertThat(ImporteHoja.leer("$ 558.154,00")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("558154"));
        assertThat(ImporteHoja.leer("$ 558.154")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("558154"));
        assertThat(ImporteHoja.leer("5.445,52")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("5445.52"));
        assertThat(ImporteHoja.leer("558154.5")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("558154.50"));
        assertThat(ImporteHoja.leer("3250")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("3250"));
    }

    @Test
    void unSeparadorCambiadoSeLeeIgualSiAgrupaDeATres() {
        // Errores de tipeo de la hoja: una coma donde va un punto, o un punto decimal.
        assertThat(ImporteHoja.leer("$ 1.071,978,46")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1071978.46"));
        assertThat(ImporteHoja.leer("$ 1.023.863.86")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1023863.86"));
    }

    @Test
    void loQueNoTieneFormatoDePlataNoSeLee() {
        assertThat(ImporteHoja.leer("$ 55a.154")).isEmpty();
        assertThat(ImporteHoja.leer("$ 1.07,978,46")).isEmpty();
        assertThat(ImporteHoja.leer("1.0238")).isEmpty();
        assertThat(ImporteHoja.leer("-5000")).isEmpty();
        assertThat(ImporteHoja.leer("")).isEmpty();
        assertThat(ImporteHoja.leer(null)).isEmpty();
    }

    @Test
    void elMesDelEncabezadoSeLeeEnVariosFormatos() {
        assertThat(MesHoja.leer("oct-26")).contains(YearMonth.of(2026, 10));
        assertThat(MesHoja.leer("SEPT-26")).contains(YearMonth.of(2026, 9));
        assertThat(MesHoja.leer("Diciembre 2026")).contains(YearMonth.of(2026, 12));
        assertThat(MesHoja.leer("agosto de 2026")).contains(YearMonth.of(2026, 8));
        assertThat(MesHoja.leer("11/2026")).contains(YearMonth.of(2026, 11));
        assertThat(MesHoja.leer("2026-11")).contains(YearMonth.of(2026, 11));
        assertThat(MesHoja.leer("BASICO")).isEmpty();
        assertThat(MesHoja.leer("Concepto")).isEmpty();
        assertThat(MesHoja.corto(YearMonth.of(2026, 10))).isEqualTo("oct-26");
    }
}
