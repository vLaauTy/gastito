package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MontosTest {

    @ParameterizedTest
    @CsvSource({
            "1500, 1500",
            "$1500, 1500",
            "1.500, 1500",
            "$1.500, 1500",
            "1.234.567, 1234567",
            "'1.500,50', 1500.50",
            "'1500,5', 1500.5",
            "2500.50, 2500.50",
            "'0,99', 0.99",
            "100, 100"
    })
    void leeMontosEnFormatoArgentino(String texto, String esperado) {
        assertThat(Montos.parsear(texto)).hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo(esperado));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "kiosco", "0", "0,00", "1,500", "15.00.00", "-100", "12a", "1.5000", "$", "1500,555"})
    void rechazaLoQueNoEsUnMonto(String texto) {
        assertThat(Montos.parsear(texto)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "380.000, 380000",
            "$380.000, 380000",
            "380 mil, 380000",
            "380mil, 380000",
            "380 MIL, 380000",
            "25k, 25000",
            "'1,5 mil', 1500",
            "'1,5 millones', 1500000",
            "1 millón, 1000000",
            "2M, 2000000",
            "'380.000,50', 380000.50"
    })
    void conPalabrasLeeMilYMillones(String texto, String esperado) {
        assertThat(Montos.parsearConPalabras(texto))
                .hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo(esperado));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "mil", "k", "sueldo", "0 mil", "-5 mil", "380 lucas", "1,5000 mil"})
    void conPalabrasRechazaLoQueNoEsUnMonto(String texto) {
        assertThat(Montos.parsearConPalabras(texto)).isEmpty();
    }

    @Test
    void elParserDeGastosNoCambia() {
        assertThat(Montos.parsear("380 mil")).isEmpty();
        assertThat(Montos.parsear("25k")).isEmpty();
    }
}
