package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DivisorDeGastosTest {

    @Test
    void separaPorComasYPorY() {
        assertThat(DivisorDeGastos.separar("Gasté 90.000 en luz, 70000 en gas y 31500 en Netflix"))
                .containsExactly("Gasté 90.000 en luz", "70000 en gas", "31500 en Netflix");
    }

    @Test
    void dosPartesAlcanzan() {
        assertThat(DivisorDeGastos.separar("kiosco 1500 y nafta 12000")).containsExactly("kiosco 1500", "nafta 12000");
    }

    @Test
    void unSoloGastoNoSeToca() {
        assertThat(DivisorDeGastos.separar("3700 en pan de pancho y papas, efectivo"))
                .containsExactly("3700 en pan de pancho y papas, efectivo");
    }

    @Test
    void siUnaParteNoTieneMontoPropioNoSeSepara() {
        assertThat(DivisorDeGastos.separar("ayer compré en el kiosco y hoy 1500 de nafta"))
                .containsExactly("ayer compré en el kiosco y hoy 1500 de nafta");
    }

    @Test
    void vacioONuloDevuelveElMismoTexto() {
        assertThat(DivisorDeGastos.separar("")).containsExactly("");
        assertThat(DivisorDeGastos.separar(null)).containsExactly((String) null);
    }
}
