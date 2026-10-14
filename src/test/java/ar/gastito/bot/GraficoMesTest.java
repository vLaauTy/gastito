package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ar.gastito.movimiento.TotalPorCategoria;

class GraficoMesTest {

    private final GraficoMes grafico = new GraficoMes(new FormatoMensajes(RELOJ));

    private static TotalPorCategoria total(String categoria, String emoji, String monto) {
        return new TotalPorCategoria(categoria, emoji, new BigDecimal(monto));
    }

    @Test
    void barrasAlineadasYProporcionales() {
        String barras = grafico.barras(List.of(
                total("Supermercado", "🛒", "30000"),
                total("Servicios (luz, gas, servicios sociales)", "💡", "15000"),
                total("Comida afuera / delivery", "🍔", "7500"),
                total("Kiosco", "🍬", "100")));

        assertThat(barras).isEqualTo("""
                <pre>🛒 Supermercado   $30.000 ▇▇▇▇▇▇▇▇▇▇▇▇
                💡 Servicios      $15.000 ▇▇▇▇▇▇
                🍔 Comida afuera…  $7.500 ▇▇▇
                🍬 Kiosco            $100 ▇</pre>""");
    }

    @Test
    void escapaHtmlYUsaUnPuntoSiNoHayEmoji() {
        assertThat(grafico.barras(List.of(total("A<B", null, "10")))).isEqualTo("<pre>• A&lt;B            $10 ▇▇▇▇▇▇▇▇▇▇▇▇</pre>");
    }

    @Test
    void sinTotalesNoHayGrafico() {
        assertThat(grafico.barras(List.of())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"30000, 30000, 12", "15000, 30000, 6", "1, 30000, 1", "0, 30000, 0", "100, 0, 0"})
    void largoDeLaBarra(String total, String maximo, int esperado) {
        assertThat(GraficoMes.largoBarra(new BigDecimal(total), new BigDecimal(maximo))).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Kiosco|Kiosco",
            "Servicios (luz, gas, servicios sociales)|Servicios",
            "Comida afuera / delivery|Comida afuera…",
            "Impuestos y cargos de tarjeta|Impuestos y c…",
            "Transporte / Moto|Transporte /…"
    })
    void nombresCortos(String nombre, String esperado) {
        assertThat(GraficoMes.nombreCorto(nombre)).isEqualTo(esperado);
        assertThat(GraficoMes.nombreCorto(nombre).length()).isLessThanOrEqualTo(GraficoMes.ANCHO_NOMBRE);
    }
}
