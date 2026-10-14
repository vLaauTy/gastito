package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

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
}
