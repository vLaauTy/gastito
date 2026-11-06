package ar.gastito.prestamo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanPrestamoTest {

    private static BigDecimal p(String monto) {
        return new BigDecimal(monto).setScale(2);
    }

    private static List<BigDecimal> pesos(String... montos) {
        return java.util.Arrays.stream(montos).map(PlanPrestamoTest::p).toList();
    }

    @Test
    void conCuotasYValorCalculaElTotal() {
        PlanPrestamo.Plan plan = PlanPrestamo.de(null, 6, new BigDecimal("5000"));
        assertThat(plan.monto()).isEqualByComparingTo("30000");
        assertThat(plan.montos()).containsExactlyElementsOf(pesos("5000", "5000", "5000", "5000", "5000", "5000"));
    }

    @Test
    void conTotalYCuotasCalculaElValorYLaUltimaAbsorbeElRedondeo() {
        PlanPrestamo.Plan plan = PlanPrestamo.de(new BigDecimal("10000"), 3, null);
        assertThat(plan.valorCuota()).isEqualByComparingTo("3333");
        assertThat(plan.montos()).containsExactlyElementsOf(pesos("3333", "3333", "3334"));
    }

    @Test
    void conTotalYValorCalculaLaCantidadYLaUltimaEsLoQueQueda() {
        PlanPrestamo.Plan plan = PlanPrestamo.de(new BigDecimal("12000"), null, new BigDecimal("5000"));
        assertThat(plan.cuotas()).isEqualTo(3);
        assertThat(plan.montos()).containsExactlyElementsOf(pesos("5000", "5000", "2000"));
    }

    @Test
    void soloElTotalEsUnaCuota() {
        assertThat(PlanPrestamo.de(new BigDecimal("8000"), null, null).montos()).containsExactly(p("8000"));
    }

    @Test
    void sinTotalNiCuotasNoHayPlan() {
        assertThatThrownBy(() -> PlanPrestamo.de(null, 6, null)).isInstanceOf(PrestamoInvalidoException.class);
    }

    @Test
    void elPagoJustoCubreUnaCuotaYLasDemasNoCambian() {
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(pesos("5000", "5000", "5000"), p("5000"));
        assertThat(a.cubiertas()).isEqualTo(1);
        assertThat(a.siguientes()).containsExactlyElementsOf(pesos("5000", "5000"));
        assertThat(a.excedente()).isZero();
    }

    @Test
    void pagarDeMenosCubreLaCuotaYLoQueFaltaSeRepartaEnLasSiguientes() {
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(pesos("5000", "5000", "5000"), p("3000"));
        assertThat(a.cubiertas()).isEqualTo(1);
        assertThat(a.cobrado()).containsExactly(p("3000"));
        assertThat(a.siguientes()).containsExactlyElementsOf(pesos("6000", "6000"));
    }

    @Test
    void pagarDeMasBajaLasSiguientes() {
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(pesos("5000", "5000", "5000", "5000"), p("7000"));
        assertThat(a.cubiertas()).isEqualTo(1);
        assertThat(a.cobrado()).containsExactly(p("7000"));
        assertThat(a.siguientes()).containsExactlyElementsOf(pesos("4333", "4333", "4334"));
    }

    @Test
    void unPagoPorDosCuotasCubreLasDos() {
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(pesos("5000", "5000", "5000"), p("10000"));
        assertThat(a.cubiertas()).isEqualTo(2);
        assertThat(a.siguientes()).containsExactly(p("5000"));
    }

    @Test
    void pagarDeMenosLaUltimaDejaUnaCuotaMasConLoQueFalta() {
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(pesos("5000"), p("4000"));
        assertThat(a.cubiertas()).isEqualTo(1);
        assertThat(a.siguientes()).containsExactly(p("1000"));
    }

    @Test
    void pagarMasQueTodoCubreTodoYDiceElExcedente() {
        PlanPrestamo.Aplicacion a = PlanPrestamo.aplicar(pesos("5000", "5000"), p("12000"));
        assertThat(a.cubiertas()).isEqualTo(2);
        assertThat(a.siguientes()).isEmpty();
        assertThat(a.excedente()).isEqualByComparingTo("2000");
    }
}
