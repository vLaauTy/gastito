package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ImportesYCodigosTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            // Los dos formatos del ticket de Día: coma en los productos, punto en el TOTAL.
            "4.500,00   | 4500.00",
            "897,60     | 897.60",
            "30666.40   | 30666.40",
            "30.666,40  | 30666.40",
            "30,666.40  | 30666.40",
            "1450,5     | 1450.50",
            "1.450      | 1450.00",
            "1.234.567  | 1234567.00",
            "1,234,567  | 1234567.00",
            "8050       | 8050.00",
            "$ 8.050,00 | 8050.00",
            "-900,00    | -900.00",
            "1.092,00-  | -1092.00",
            "(250)      | -250.00",
            "2250.123   | 2250.12",
    })
    void unImporteEnCualquieraDeLosDosFormatos(String texto, String esperado) {
        assertThat(Importes.importe(texto)).get().isEqualTo(new BigDecimal(esperado));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "1.2.3,4,5x", "12-34"})
    void loQueNoEsUnImporte(String texto) {
        assertThat(Importes.importe(texto)).isEmpty();
        assertThat(Importes.importe(null)).isEmpty();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {"2 | 2", "0,532 | 0.532", "0.532 | 0.532", "1.5 | 1.5", "1.500 | 1.5"})
    void unaCantidadConUnSeparadorEsDecimal(String texto, String esperado) {
        assertThat(Importes.cantidad(texto)).get().satisfies(c -> assertThat(c).isEqualByComparingTo(esperado));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "x"})
    void unaCantidadTieneQueSerPositiva(String texto) {
        assertThat(Importes.cantidad(texto)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Los del ticket real de Día.
            "7792180145444", "7790070432858", "7790250016182", "7790310983256", "7794000009547", "7791813777021",
            "7509546700434", "7792360094968", "7509546075242", "8480017056221",
            // EAN-8.
            "96385074", "40170725"})
    void unEanConSuDigitoVerificadorSeGuarda(String codigo) {
        assertThat(CodigoBarras.validar(codigo)).contains(codigo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7792180145445", "779218014544", "12345", "77921801454444", "ABCDEFGH", "96385075"})
    void unCodigoQueNoEsEanSeDescarta(String codigo) {
        assertThat(CodigoBarras.validar(codigo)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void unEanConEspaciosOGuionesSeLimpia() {
        assertThat(CodigoBarras.validar("779 2180 14544 4")).contains("7792180145444");
        assertThat(CodigoBarras.validar("9638-5074")).contains("96385074");
        assertThat(CodigoBarras.validar(null)).isEmpty();
    }
}
