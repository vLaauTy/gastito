package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ReglaRepetidosTest {

    static final LocalDateTime DIA = LocalDateTime.of(2026, 10, 20, 13, 0);
    static final Duration TRES_HORAS = Duration.ofHours(3);
    static final Long SUPER = 2L;
    static final Long KIOSCO = 1L;

    private static Huella h(String monto, LocalDateTime fecha, Long categoria, String comercio) {
        return new Huella(null, fecha, new BigDecimal(monto), categoria, comercio);
    }

    private static boolean parecidos(Huella a, Huella b) {
        return ReglaRepetidos.parecidos(a, b, TRES_HORAS);
    }

    @Test
    void elMismoMontoYCategoriaAUnaHoraEsLaMismaCompra() {
        assertThat(parecidos(h("8050", DIA, SUPER, null), h("8050", DIA.plusHours(1), SUPER, null))).isTrue();
    }

    @Test
    void laToleranciaEsDel1PorCientoOCincuentaPesos() {
        // Montos chicos: $50.
        assertThat(parecidos(h("1500", DIA, KIOSCO, null), h("1550", DIA, KIOSCO, null))).isTrue();
        assertThat(parecidos(h("1500", DIA, KIOSCO, null), h("1551", DIA, KIOSCO, null))).isFalse();
        // Montos grandes: el 1% del mayor (48.000 → $480).
        assertThat(parecidos(h("47520", DIA, SUPER, null), h("48000", DIA, SUPER, null))).isTrue();
        assertThat(parecidos(h("47500", DIA, SUPER, null), h("48000", DIA, SUPER, null))).isFalse();
    }

    @Test
    void laVentanaEsDeTresHorasParaAntesYParaDespues() {
        Huella nuevo = h("8050", DIA, SUPER, null);

        assertThat(parecidos(nuevo, h("8050", DIA.minusHours(3), SUPER, null))).isTrue();
        assertThat(parecidos(nuevo, h("8050", DIA.plusHours(3), SUPER, null))).isTrue();
        assertThat(parecidos(nuevo, h("8050", DIA.minusHours(3).minusMinutes(1), SUPER, null))).isFalse();
        assertThat(parecidos(nuevo, h("8050", DIA.plusHours(3).plusMinutes(1), SUPER, null))).isFalse();
        // Sin ventana (el cobro de un fijo del mes) no se mira la hora.
        assertThat(ReglaRepetidos.parecidos(nuevo, h("8050", DIA.minusDays(10), SUPER, null), null)).isTrue();
    }

    @Test
    void conCategoriasDistintasNoEsLaMismaCompraSalvoQueCoincidaElComercio() {
        assertThat(parecidos(h("8050", DIA, SUPER, null), h("8050", DIA, KIOSCO, null))).isFalse();
        assertThat(parecidos(h("8050", DIA, SUPER, "DIA TIENDA 5601"), h("8050", DIA, KIOSCO, "Día"))).isFalse();
        assertThat(parecidos(h("8050", DIA, SUPER, "Día"), h("8050", DIA, KIOSCO, "DÍA"))).isTrue();
    }

    @Test
    void siUnoNoTieneCategoriaNiComercioAlcanzanElMontoYLaHora() {
        assertThat(parecidos(h("8050", DIA, null, null), h("8050", DIA, SUPER, "Día"))).isTrue();
        assertThat(parecidos(h("8050", DIA, SUPER, null), h("8050", DIA, null, "Día"))).isTrue();
    }

    @Test
    void elComercioSeComparaSinSucursalNiTildes() {
        assertThat(h("1", DIA, null, "DIA TIENDA 5601").comercioNormalizado()).isEqualTo("dia tienda");
        assertThat(h("1", DIA, null, "FEDERA8//4017901480026").comercioNormalizado()).isEqualTo("federa8");
        assertThat(h("1", DIA, null, "Día").comercioNormalizado()).isEqualTo("dia");
        assertThat(h("1", DIA, null, " 123 ").comercioNormalizado()).isNull();
    }
}
