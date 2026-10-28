package ar.gastito.prueba;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanSueldoPruebaTest {

    /** Los conceptos con monto o porcentaje que deja la migración V12. */
    static final List<String> CONCEPTOS_CON_VALOR = List.of("Sueldo básico", "Presentismo", "Adicional CCT",
            "Asignación integración social", "Viático", "Asignación extraordinaria", "Jubilación", "Obra social",
            "Ley 19.032", "Sindicato");

    private final List<PlanSueldoPrueba.Escala> escalas = PlanSueldoPrueba.escalas(YearMonth.of(2026, 10));

    @Test
    void hayDosEscalasYLaParitariaRigeDesdeLaMitadDeLosSeisMeses() {
        assertThat(escalas).extracting(PlanSueldoPrueba.Escala::desde)
                .containsExactly(YearMonth.of(2026, 5), YearMonth.of(2026, 8));
    }

    @Test
    void cadaEscalaTieneUnValorParaCadaConcepto() {
        for (PlanSueldoPrueba.Escala e : escalas) {
            assertThat(e.montos().keySet()).containsExactlyInAnyOrderElementsOf(CONCEPTOS_CON_VALOR);
        }
    }

    @Test
    void laParitariaSubeLosMontosYDejaLosPorcentajes() {
        PlanSueldoPrueba.Escala antes = escalas.get(0);
        PlanSueldoPrueba.Escala despues = escalas.get(1);

        assertThat(despues.valorHora()).isGreaterThan(antes.valorHora());
        assertThat(despues.montos().get("Sueldo básico")).isGreaterThan(antes.montos().get("Sueldo básico"));
        assertThat(despues.montos().get("Jubilación")).isEqualByComparingTo(antes.montos().get("Jubilación"));
    }

    @Test
    void lasHorasDeCadaMesSonTresNumerosNoNegativos() {
        for (int i = 0; i < PlanSueldoPrueba.MESES; i++) {
            assertThat(PlanSueldoPrueba.horas(i)).hasSize(3).allSatisfy(h -> assertThat(h).isGreaterThanOrEqualTo(
                    BigDecimal.ZERO));
        }
    }

    @Test
    void elMesRaroEstaDentroDeLosSeisYNoEsElUltimo() {
        assertThat(PlanSueldoPrueba.MES_RARO).isBetween(0, PlanSueldoPrueba.MESES - 2);
    }
}
