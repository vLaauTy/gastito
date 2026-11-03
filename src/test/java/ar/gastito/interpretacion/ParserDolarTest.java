package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParserDolarTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "el dólar tarjeta está a 1500|1500",
            "El dolar esta a $1.620|1620",
            "dólar 1500|1500",
            "dolar tarjeta 1.480,50|1480.50",
            "el dólar de la tarjeta está en 1500.|1500",
            "dólar 1,5 mil|1500"})
    void laCotizacionDichaDeFormaObviaSeEntiendeSinIa(String texto, String valor) {
        assertThat(ParserDolar.cotizacion(texto)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo(valor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pagué 20 dólares de Steam", "a cuánto está el dólar?", "dólar", "kiosco 1500",
            "el dólar subió mucho"})
    void loQueNoEsLaCotizacionNoSeToma(String texto) {
        assertThat(ParserDolar.cotizacion(texto)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pagué 20 dólares de Steam con la tarjeta", "US$ 15 en Spotify", "u$s 9,99 netflix",
            "15 USD de iCloud", "steam 20 dolares", "15 verdes en una app"})
    void reconoceCuandoHablaDeDolares(String texto) {
        assertThat(ParserDolar.mencionaDolares(texto)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"kiosco 1500", "3700 en pan", "pagué 12000 de nafta con la tarjeta", "verduleria 2000"})
    void enPesosNoHablaDeDolares(String texto) {
        assertThat(ParserDolar.mencionaDolares(texto)).isFalse();
    }
}
