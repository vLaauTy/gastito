package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClasificadorIntencionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "¿Cuánto gasté en kiosco este mes?",
            "cuanto gaste en kiosco",
            "¿En qué gasté más la semana pasada?",
            "Cuántas Seven Up compré este mes",
            "¿Cuánto me queda disponible?",
            "Compará octubre con septiembre",
            "cuánto gasté del 3 al 10 de octubre",
            "¿cuánto gasté en 2026?",
            "¿cuánto llevo gastado en Día desde septiembre?",
    })
    void lasPreguntasObviasNoNecesitanLaIa(String texto) {
        assertThat(ClasificadorIntencion.esPreguntaObvia(texto)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "¿cuánto salió la nafta? 9000", // tiene un monto: decide la IA
            "¿cuánto gasté? 1.500 en pan",
            "kiosco 1500",
            "3700 en pan de pancho y papas",
            "pagué 15 mil de nafta",
            "super 25k",
            "pan",
            "hola",
            "",
    })
    void conMontoOSinFormaDePreguntaNoSonPreguntasObvias(String texto) {
        assertThat(ClasificadorIntencion.esPreguntaObvia(texto)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1500", "1.500", "1.500,50", "15 mil", "25k", "gasté 300"})
    void reconoceMontos(String texto) {
        assertThat(ClasificadorIntencion.tieneMonto(texto)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"del 3 al 10", "en 2026", "el 12/10", "12/10/2026", "semana 42"})
    void diasAniosYFechasNoSonMontos(String texto) {
        assertThat(ClasificadorIntencion.tieneMonto(texto)).isFalse();
    }
}
