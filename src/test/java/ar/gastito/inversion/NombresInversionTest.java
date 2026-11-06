package ar.gastito.inversion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NombresInversionTest {

    @Test
    void normalizaSinTildesNiMayusculas() {
        assertThat(NombresInversion.normalizar("  Acción   YPF ")).isEqualTo("accion ypf");
    }

    @Test
    void limpiaElArticuloYLaPuntuacion() {
        assertThat(NombresInversion.limpiar(" un FCI.")).isEqualTo("FCI");
        assertThat(NombresInversion.limpiar("cedear SPY")).isEqualTo("Cedear SPY");
    }

    @Test
    void losAliasSonLoDistintivo() {
        assertThat(NombresInversion.palabrasClave("CEDEAR SPY")).isEqualTo("spy");
        assertThat(NombresInversion.palabrasClave("FCI money market")).isEqualTo("money market");
        assertThat(NombresInversion.palabrasClave("Acción YPF")).isEqualTo("ypf");
        assertThat(NombresInversion.palabrasClave("FCI")).isNull();
        assertThat(NombresInversion.palabrasClave("Bitcoin")).isNull();
    }
}
