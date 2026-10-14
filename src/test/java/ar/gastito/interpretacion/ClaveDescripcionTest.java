package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClaveDescripcionTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Pan de pancho y papas|pan pancho papas",
            "papas y pan de pancho|pan pancho papas",
            "PAN DE PANCHO, PAPAS!|pan pancho papas",
            "en pan de pancho y papas|pan pancho papas",
            "Compré café con leche|cafe leche",
            "Nafta|nafta",
            "Coca 2.25L|2 25l coca"
    })
    void normaliza(String descripcion, String clave) {
        assertThat(ClaveDescripcion.de(descripcion)).isEqualTo(clave);
    }

    @Test
    void vacios() {
        assertThat(ClaveDescripcion.de(null)).isEmpty();
        assertThat(ClaveDescripcion.de("  ,. ")).isEmpty();
        assertThat(ClaveDescripcion.de("de la y en")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pan pancho papas|pan pancho papas|true",
            "pan pacho papas|pan pancho papas|true", // falta una letra
            "pacho pan papas|pan pancho papas|true", // el error de tipeo cambió el orden de las palabras
            "pan pancho papa|pan pancho papas|true",
            "pa pancho papas|pan pancho papas|false", // palabras cortas: tienen que ser iguales
            "pancho pan papitas|pan pancho papas|true", // 2 letras: todavía es el mismo gasto
            "pancho pan patatas|pan pancho papas|false", // demasiadas letras distintas
            "pan pancho|pan pancho papas mayonesa|false",
            "pan panchos papas|pan pancho papas|true",
            "nafta|nafte|false", // muy cortas: tienen que ser iguales
            "nafta|nafta|true",
            "pan pancho|pan pancho papas|false",
            "alfajor|alfajores|false"
    })
    void parecidas(String a, String b, boolean esperado) {
        assertThat(ClaveDescripcion.parecidas(a, b)).isEqualTo(esperado);
    }

    @Test
    void clavesVaciasNoSeParecenANada() {
        assertThat(ClaveDescripcion.parecidas("", "")).isFalse();
    }

    @Test
    void distancia() {
        assertThat(ClaveDescripcion.distancia("pancho", "pacho")).isEqualTo(1);
        assertThat(ClaveDescripcion.distancia("kiosco", "kiosko")).isEqualTo(1);
        assertThat(ClaveDescripcion.distancia("", "abc")).isEqualTo(3);
    }
}
