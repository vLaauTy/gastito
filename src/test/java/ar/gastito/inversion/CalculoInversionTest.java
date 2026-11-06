package ar.gastito.inversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.inversion.CalculoInversion.Aporte;
import ar.gastito.inversion.CalculoInversion.Clave;

/** El rendimiento: valor actual − lo puesto, en pesos y en %. */
class CalculoInversionTest {

    static final Clave BOLSA = new Clave(1, null);
    static final Clave SPY = new Clave(1, 10L);
    static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    private static FuenteDeValuaciones.Valuacion valuacion(Clave c, LocalDate fecha, String valor,
            OrigenValuacion origen) {
        return new FuenteDeValuaciones.Valuacion(fecha.toEpochDay(), c.cuentaId(), c.posicionId(), fecha,
                new BigDecimal(valor), origen, fecha.atStartOfDay());
    }

    @Test
    void sinValuacionesValeLoQueSePuso() {
        CalculoInversion.Estado e = CalculoInversion.estado(BOLSA,
                List.of(new Aporte(BOLSA, OCT_1, new BigDecimal("850000"))), List.of(), OCT_1.plusDays(10));

        assertThat(e.sinValuar()).isTrue();
        assertThat(e.valor()).isEqualByComparingTo("850000");
        assertThat(e.rendimiento().pesos()).isEqualByComparingTo("0");
        assertThat(e.rendimiento().porcentaje()).isEqualByComparingTo("0");
    }

    @Test
    void ganancia() {
        List<Aporte> aportes = List.of(new Aporte(SPY, OCT_1, new BigDecimal("500000")));
        CalculoInversion.Estado e = CalculoInversion.estado(SPY, aportes,
                List.of(valuacion(SPY, OCT_1.plusDays(7), "520000", OrigenValuacion.MANUAL)), OCT_1.plusDays(30));

        assertThat(e.valor()).isEqualByComparingTo("520000");
        assertThat(e.rendimiento().pesos()).isEqualByComparingTo("20000");
        assertThat(e.rendimiento().porcentaje()).isEqualByComparingTo("4.00");
        assertThat(e.rendimiento().esGanancia()).isTrue();
        assertThat(e.ultimaActualizacion()).isEqualTo(OCT_1.plusDays(7));
    }

    @Test
    void perdidaConAportesYRetiros() {
        // 850.000 iniciales + 200.000 de aporte − 150.000 de retiro = 900.000 puestos; vale 870.000.
        List<Aporte> aportes = List.of(new Aporte(BOLSA, OCT_1, new BigDecimal("850000")),
                new Aporte(BOLSA, OCT_1.plusDays(3), new BigDecimal("200000")),
                new Aporte(BOLSA, OCT_1.plusDays(5), new BigDecimal("-150000")));
        CalculoInversion.Estado e = CalculoInversion.estado(BOLSA, aportes,
                List.of(valuacion(BOLSA, OCT_1.plusDays(6), "870000", OrigenValuacion.MANUAL)), OCT_1.plusDays(6));

        assertThat(e.aportado()).isEqualByComparingTo("900000");
        assertThat(e.rendimiento().pesos()).isEqualByComparingTo("-30000");
        assertThat(e.rendimiento().porcentaje()).isEqualByComparingTo("-3.33");
        assertThat(e.rendimiento().esPerdida()).isTrue();
    }

    @Test
    void unAjusteNoCuentaComoActualizacion() {
        List<FuenteDeValuaciones.Valuacion> valuaciones = List.of(
                valuacion(BOLSA, OCT_1, "100000", OrigenValuacion.MANUAL),
                valuacion(BOLSA, OCT_1.plusDays(4), "150000", OrigenValuacion.AJUSTE));
        CalculoInversion.Estado e = CalculoInversion.estado(BOLSA, List.of(), valuaciones, OCT_1.plusDays(10));

        assertThat(e.valor()).isEqualByComparingTo("150000");
        assertThat(e.ultimaValuacion()).isEqualTo(OCT_1.plusDays(4));
        assertThat(e.ultimaActualizacion()).isEqualTo(OCT_1);
    }

    @Test
    void sinNadaPuestoNoHayPorcentaje() {
        assertThat(CalculoInversion.rendimiento(new BigDecimal("1000"), BigDecimal.ZERO).porcentaje()).isNull();
    }

    @Test
    void laSerieSumaLasTenenciasEnCadaFecha() {
        List<Aporte> aportes = List.of(new Aporte(BOLSA, OCT_1, new BigDecimal("300000")),
                new Aporte(SPY, OCT_1, new BigDecimal("500000")));
        List<FuenteDeValuaciones.Valuacion> valuaciones = List.of(
                valuacion(SPY, OCT_1.plusDays(7), "520000", OrigenValuacion.MANUAL));

        List<CalculoInversion.Punto> serie = CalculoInversion.serie(List.of(BOLSA, SPY), aportes, valuaciones);

        assertThat(serie).extracting(CalculoInversion.Punto::fecha).containsExactly(OCT_1, OCT_1.plusDays(7));
        assertThat(serie.get(0).valor()).isEqualByComparingTo("800000");
        assertThat(serie.get(1).aportado()).isEqualByComparingTo("800000");
        assertThat(serie.get(1).valor()).isEqualByComparingTo("820000");
    }

    @Test
    void laUltimaValuacionDelDiaManda() {
        LocalDateTime temprano = OCT_1.atTime(9, 0);
        List<FuenteDeValuaciones.Valuacion> valuaciones = List.of(
                new FuenteDeValuaciones.Valuacion(1L, 1, null, OCT_1, new BigDecimal("100"), OrigenValuacion.MANUAL,
                        temprano),
                new FuenteDeValuaciones.Valuacion(2L, 1, null, OCT_1, new BigDecimal("120"), OrigenValuacion.MANUAL,
                        temprano.plusHours(3)));

        assertThat(CalculoInversion.estado(BOLSA, List.of(), valuaciones, OCT_1).valor()).isEqualByComparingTo("120");
    }
}
