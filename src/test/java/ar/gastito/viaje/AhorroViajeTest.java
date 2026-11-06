package ar.gastito.viaje;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Hoy es el 6/11/2026 y el viaje empieza el 10/1/2027. El sueldo se cobra los 5. */
class AhorroViajeTest {

    static final LocalDate HOY = LocalDate.of(2026, 11, 6);
    static final LocalDate INICIO = LocalDate.of(2027, 1, 10);

    @Test
    void sinReservasSeDivideLoQueFaltaPorLosCobrosQueQuedan() {
        // Noviembre ya se cobró: quedan el 5/12 y el 5/1.
        AhorroViaje a = AhorroViaje.calcular(new BigDecimal("800000"), BigDecimal.ZERO, HOY, INICIO, 5, true);

        assertThat(a.cobros()).isEqualTo(2);
        assertThat(a.porMes()).isEqualByComparingTo("400000");
    }

    @Test
    void loReservadoEnInversionesSeDescuenta() {
        AhorroViaje a = AhorroViaje.calcular(new BigDecimal("800000"), new BigDecimal("200000"), HOY, INICIO, 5, true);

        assertThat(a.aSeparar()).isEqualByComparingTo("600000");
        assertThat(a.porMes()).isEqualByComparingTo("300000");
    }

    @Test
    void siLoReservadoAlcanzaNoHayQueSepararNada() {
        AhorroViaje a = AhorroViaje.calcular(new BigDecimal("100000"), new BigDecimal("150000"), HOY, INICIO, 5, true);

        assertThat(a.aSeparar()).isZero();
        assertThat(a.porMes()).isZero();
    }

    @Test
    void elSueldoDeEsteMesCuentaSiTodaviaNoEntro() {
        assertThat(AhorroViaje.cobros(HOY, INICIO, 5, false)).isEqualTo(3);
        assertThat(AhorroViaje.cobros(LocalDate.of(2026, 11, 3), INICIO, 5, false)).isEqualTo(3);
    }

    @Test
    void unCobroElMismoDiaQueEmpiezaElViajeNoCuenta() {
        assertThat(AhorroViaje.cobros(HOY, LocalDate.of(2027, 1, 5), 5, true)).isEqualTo(1);
    }

    @Test
    void sinCobrosAntesDelViajeNoHayPorMes() {
        AhorroViaje a = AhorroViaje.calcular(new BigDecimal("50000"), BigDecimal.ZERO, HOY,
                LocalDate.of(2026, 11, 20), 5, true);

        assertThat(a.cobros()).isZero();
        assertThat(a.porMes()).isNull();
    }

    @Test
    void elPorMesSeRedondeaParaArriba() {
        assertThat(AhorroViaje.calcular(new BigDecimal("100000"), BigDecimal.ZERO, HOY,
                LocalDate.of(2027, 2, 10), 5, true).porMes()).isEqualByComparingTo("33334");
    }
}
