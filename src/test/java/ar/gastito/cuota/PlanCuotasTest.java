package ar.gastito.cuota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class PlanCuotasTest {

    private static PlanCuotas plan(BigDecimal total, int cantidad, BigDecimal valorCuota, LocalDateTime fechaCompra,
            YearMonth primerMes) {
        return new PlanCuotas("Zapatillas", total, cantidad, valorCuota, fechaCompra, primerMes, null, null,
                LocalDateTime.now());
    }

    @Test
    void elValorDeCadaCuotaSeRedondeaYElTotalQuedaComoSeDijo() {
        PlanCuotas p = plan(new BigDecimal("100000"), 3, new BigDecimal("33333.33"),
                LocalDateTime.of(2026, 10, 20, 18, 0), YearMonth.of(2026, 10));

        assertThat(p.getTotal()).isEqualByComparingTo("100000");
        assertThat(p.getValorCuota()).isEqualByComparingTo("33333.33");
    }

    @Test
    void fechaDeCuotaUsaElMismoDiaDelMesQueLaCompraClampeadoAlMes() {
        // Compra el 31/10: la cuota 1 es octubre (31), la 4 enero (31) y la 5 febrero (28, no bisiesto).
        PlanCuotas p = plan(new BigDecimal("60000"), 6, new BigDecimal("10000"),
                LocalDateTime.of(2026, 10, 31, 12, 0), YearMonth.of(2026, 10));

        assertThat(p.fechaDeCuota(1)).isEqualTo(LocalDateTime.of(2026, 10, 31, 12, 0));
        assertThat(p.fechaDeCuota(4)).isEqualTo(LocalDateTime.of(2027, 1, 31, 12, 0));
        assertThat(p.fechaDeCuota(5)).isEqualTo(LocalDateTime.of(2027, 2, 28, 12, 0));
    }

    @Test
    void cantidadMenorA2OValoresNoPositivosFallan() {
        LocalDateTime ahora = LocalDateTime.of(2026, 10, 20, 18, 0);
        YearMonth mes = YearMonth.of(2026, 10);

        assertThatThrownBy(() -> plan(new BigDecimal("1000"), 1, new BigDecimal("1000"), ahora, mes))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(BigDecimal.ZERO, 3, new BigDecimal("1000"), ahora, mes))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(new BigDecimal("1000"), 3, BigDecimal.ZERO, ahora, mes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelarUnPlanYaCanceladoFalla() {
        PlanCuotas p = plan(new BigDecimal("1000"), 2, new BigDecimal("500"), LocalDateTime.now(), YearMonth.now());
        p.cancelar();

        assertThatThrownBy(p::cancelar).isInstanceOf(IllegalStateException.class);
    }
}
