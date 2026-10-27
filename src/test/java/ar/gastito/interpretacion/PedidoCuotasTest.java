package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PedidoCuotasTest {

    @Test
    void conElTotalDichoElValorDeCadaCuotaLoCalculaJava() {
        assertThat(PedidoCuotas.de("6", "", new BigDecimal("84000")))
                .isEqualTo(new PedidoCuotas(6, new BigDecimal("84000.00"), new BigDecimal("14000.00")));
    }

    @Test
    void siSoloSeDiceElValorDeCadaCuotaElTotalSaleDeMultiplicar() {
        assertThat(PedidoCuotas.de("6", "14000", null))
                .isEqualTo(new PedidoCuotas(6, new BigDecimal("84000.00"), new BigDecimal("14000.00")));
    }

    @Test
    void sinCantidadOFueraDeRangoNoEsUnPlan() {
        assertThat(PedidoCuotas.de("", "14000", null)).isNull();
        assertThat(PedidoCuotas.de("1", "14000", null)).isNull();
        assertThat(PedidoCuotas.de("100", "100", new BigDecimal("10000"))).isNull(); // más de 24: no es creíble
    }

    @Test
    void sinTotalNiValorDeCuotaNoEsUnPlan() {
        assertThat(PedidoCuotas.de("6", "", null)).isNull();
    }

    @Test
    void elValorDeCadaCuotaSeRedondeaAlCentavo() {
        PedidoCuotas p = PedidoCuotas.de("3", "", new BigDecimal("100000"));

        assertThat(p.total()).isEqualByComparingTo("100000");
        assertThat(p.valorCuota()).isEqualByComparingTo("33333.33");
    }
}
