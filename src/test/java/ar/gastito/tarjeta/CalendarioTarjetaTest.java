package ar.gastito.tarjeta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

/** El resumen real: cerró el jueves 29/10/2026 y vence el miércoles 11/11 (13 días). */
class CalendarioTarjetaTest {

    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);
    static final YearMonth NOVIEMBRE = YearMonth.of(2026, 11);
    static final YearMonth DICIEMBRE = YearMonth.of(2026, 12);

    static final CalendarioTarjeta.Fechas CONFIRMADO_OCTUBRE = new CalendarioTarjeta.Fechas(OCTUBRE,
            LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11), true);

    private static CalendarioTarjeta soloOctubre() {
        return new CalendarioTarjeta(List.of(CONFIRMADO_OCTUBRE));
    }

    @Test
    void sinConfirmadosEstaVacio() {
        CalendarioTarjeta calendario = new CalendarioTarjeta(List.of(new CalendarioTarjeta.Fechas(OCTUBRE,
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11), false)));

        assertThat(calendario.isVacio()).isTrue();
    }

    @Test
    void elConfirmadoTieneSusFechas() {
        assertThat(soloOctubre().fechas(OCTUBRE)).isEqualTo(CONFIRMADO_OCTUBRE);
    }

    @Test
    void estimaElMismoDiaCorridoAlLunesYLaMismaDistanciaHastaElVencimiento() {
        // 29/11 es domingo → lunes 30/11; 30/11 + 13 = domingo 13/12 → lunes 14/12.
        CalendarioTarjeta.Fechas noviembre = soloOctubre().fechas(NOVIEMBRE);

        assertThat(noviembre.cierre()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(noviembre.vencimiento()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(noviembre.estimadas()).isTrue();
    }

    @Test
    void estimaSiempreDesdeElConfirmadoSinCorrerseMesAMes() {
        // Diciembre sale de octubre (día 29), no de noviembre estimado (día 30): martes 29/12, vence lunes 11/1.
        CalendarioTarjeta.Fechas diciembre = soloOctubre().fechas(DICIEMBRE);

        assertThat(diciembre.cierre()).isEqualTo(LocalDate.of(2026, 12, 29));
        assertThat(diciembre.vencimiento()).isEqualTo(LocalDate.of(2027, 1, 11));
    }

    @Test
    void haciaAtrasTambienEstimaDesdeElPrimerConfirmado() {
        assertThat(soloOctubre().fechas(YearMonth.of(2026, 9)).cierre()).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @Test
    void elDiaDelCierreEntraEnEseResumenYElSiguienteEnElProximo() {
        CalendarioTarjeta calendario = soloOctubre();

        assertThat(calendario.periodoDe(LocalDate.of(2026, 10, 29))).isEqualTo(OCTUBRE);
        assertThat(calendario.periodoDe(LocalDate.of(2026, 10, 30))).isEqualTo(NOVIEMBRE);
        assertThat(calendario.periodoDe(LocalDate.of(2026, 11, 30))).isEqualTo(NOVIEMBRE);
        assertThat(calendario.periodoDe(LocalDate.of(2026, 12, 1))).isEqualTo(DICIEMBRE);
        assertThat(calendario.periodoDe(LocalDate.of(2026, 10, 1))).isEqualTo(OCTUBRE);
        assertThat(calendario.periodoDe(LocalDate.of(2026, 9, 29))).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void elPeriodoEmpiezaElDiaSiguienteAlCierreAnterior() {
        assertThat(soloOctubre().desde(NOVIEMBRE)).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void conLasFechasConfirmadasLasComprasSeReasignan() {
        CalendarioTarjeta antes = soloOctubre();
        CalendarioTarjeta despues = new CalendarioTarjeta(List.of(CONFIRMADO_OCTUBRE, new CalendarioTarjeta.Fechas(
                NOVIEMBRE, LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11), true)));
        LocalDate compra = LocalDate.of(2026, 11, 28);

        assertThat(antes.periodoDe(compra)).isEqualTo(NOVIEMBRE);
        assertThat(despues.periodoDe(compra)).isEqualTo(DICIEMBRE);
        // Diciembre ahora sale de noviembre: 26/12 es sábado → lunes 28/12; + 15 días = martes 12/1.
        assertThat(despues.fechas(DICIEMBRE).cierre()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(despues.fechas(DICIEMBRE).vencimiento()).isEqualTo(LocalDate.of(2027, 1, 12));
    }

    @Test
    void unCierreCorridoAlMesSiguienteEsDelResumenAnterior() {
        CalendarioTarjeta calendario = soloOctubre();

        assertThat(calendario.periodoParaCierre(LocalDate.of(2026, 12, 1))).isEqualTo(NOVIEMBRE);
        assertThat(calendario.periodoParaCierre(LocalDate.of(2026, 11, 26))).isEqualTo(NOVIEMBRE);
        assertThat(calendario.periodoParaCierre(LocalDate.of(2026, 12, 22))).isEqualTo(DICIEMBRE);
    }

    @Test
    void unAnclaQueCerroElMesSiguienteSeEstimaAFinDeMes() {
        // Noviembre cerró el martes 1/12 (corrido): se toma el 30 como día de cierre.
        CalendarioTarjeta calendario = new CalendarioTarjeta(List.of(new CalendarioTarjeta.Fechas(NOVIEMBRE,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 14), true)));

        assertThat(calendario.fechas(DICIEMBRE).cierre()).isEqualTo(LocalDate.of(2026, 12, 30));
        // Febrero no tiene 30: el 28, que es domingo → lunes 1/3 (sigue siendo el resumen de febrero).
        assertThat(calendario.fechas(YearMonth.of(2027, 2)).cierre()).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(calendario.periodoDe(LocalDate.of(2027, 3, 1))).isEqualTo(YearMonth.of(2027, 2));
    }

    @Test
    void habilPasaElFinDeSemanaAlLunes() {
        assertThat(CalendarioTarjeta.habil(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(CalendarioTarjeta.habil(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(CalendarioTarjeta.habil(LocalDate.of(2026, 10, 30))).isEqualTo(LocalDate.of(2026, 10, 30));
    }
}
