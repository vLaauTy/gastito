package ar.gastito.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ar.gastito.config.GastitoProperties;

class CalculadoraCostoIaTest {

    private final CalculadoraCostoIa calculadora = new CalculadoraCostoIa(new GastitoProperties(
            ZoneId.of("America/Argentina/Buenos_Aires"), null,
            new GastitoProperties.Ia("key", "claude-haiku-5-5", 1024, Map.of(
                    "claude-haiku-5-5", new GastitoProperties.Precio(new BigDecimal("0.10"), new BigDecimal("0.50"))))));

    @Test
    void unMillonDeTokensCuestaElPrecioPorMillon() {
        assertThat(calculadora.costo("claude-haiku-5-5", 1_000_000, 0)).isEqualByComparingTo("0.10");
        assertThat(calculadora.costo("claude-haiku-5-5", 0, 1_000_000)).isEqualByComparingTo("0.50");
    }

    @Test
    void sumaEntradaYSalida() {
        // 800 × 0,10 / 1M + 120 × 0,50 / 1M = 0,00008 + 0,00006
        assertThat(calculadora.costo("claude-haiku-5-5", 800, 120)).isEqualByComparingTo("0.00014");
    }

    @Test
    void unSoloTokenNoSePierdePorRedondeo() {
        assertThat(calculadora.costo("claude-haiku-5-5", 1, 0)).isEqualByComparingTo("0.0000001");
        assertThat(calculadora.costo("claude-haiku-5-5", 0, 1)).isEqualByComparingTo("0.0000005");
    }

    @Test
    void usaLaEscalaDeLaColumna() {
        assertThat(calculadora.costo("claude-haiku-5-5", 123, 45).scale()).isEqualTo(CalculadoraCostoIa.ESCALA);
    }

    @Test
    void sinTokensNoCuestaNada() {
        assertThat(calculadora.costo("claude-haiku-5-5", 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void modeloSinPrecioSeRegistraEnCero() {
        assertThat(calculadora.costo("modelo-desconocido", 1000, 1000)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
