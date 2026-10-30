package ar.gastito.sueldo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Valores inventados: no son los de ningún sueldo real. */
class CalculadoraSueldoTest {

    static final ConceptoSueldo BASICO = concepto(1, "Básico", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo PRESENTISMO = concepto(2, "Presentismo", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo ADICIONAL = concepto(3, "Adicional", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo INTEGRACION = concepto(4, "Integración", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo AL_50 = concepto(5, "Horas al 50%", TipoConceptoSueldo.HORAS, 3, 2);
    static final ConceptoSueldo AL_100 = concepto(6, "Horas al 100%", TipoConceptoSueldo.HORAS, 2, 1);
    static final ConceptoSueldo NOCTURNAS = concepto(7, "Horas nocturnas", TipoConceptoSueldo.HORAS, 8, 60);
    static final ConceptoSueldo VIATICO = concepto(8, "Viático", TipoConceptoSueldo.NO_REMUNERATIVO, null, null);
    static final ConceptoSueldo EXTRAORDINARIA = concepto(9, "Extraordinaria", TipoConceptoSueldo.NO_REMUNERATIVO, null, null);
    static final ConceptoSueldo JUBILACION = concepto(10, "Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo OBRA_SOCIAL = concepto(11, "Obra social", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo LEY = concepto(12, "Ley 19.032", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final ConceptoSueldo SINDICATO = concepto(13, "Sindicato", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);

    static final List<ConceptoSueldo> CONCEPTOS = List.of(BASICO, PRESENTISMO, ADICIONAL, INTEGRACION, AL_50, AL_100,
            NOCTURNAS, VIATICO, EXTRAORDINARIA, JUBILACION, OBRA_SOCIAL, LEY, SINDICATO);

    static final Map<Long, BigDecimal> HORAS = Map.of(AL_50.getId(), bd("10"), AL_100.getId(), bd("6"),
            NOCTURNAS.getId(), bd("30"));

    static ConceptoSueldo concepto(long id, String nombre, TipoConceptoSueldo tipo, Integer num, Integer den) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, tipo, num, den, (int) id));
    }

    static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    static EscalaSueldo escala(String desde, String hora, String basico) {
        return new EscalaSueldo(LocalDate.parse(desde), bd(hora), Map.ofEntries(
                Map.entry(BASICO.getId(), bd(basico)),
                Map.entry(PRESENTISMO.getId(), bd("100000")),
                Map.entry(ADICIONAL.getId(), bd("90000")),
                Map.entry(INTEGRACION.getId(), bd("60000")),
                Map.entry(VIATICO.getId(), bd("300000")),
                Map.entry(EXTRAORDINARIA.getId(), bd("140000")),
                Map.entry(JUBILACION.getId(), bd("11")),
                Map.entry(OBRA_SOCIAL.getId(), bd("3")),
                Map.entry(LEY.getId(), bd("3")),
                Map.entry(SINDICATO.getId(), bd("3"))));
    }

    @Test
    void calculaElCasoDelPedido() {
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala("2026-01-01", "3000", "500000"),
                HORAS, Map.of());

        assertThat(r.bruto()).isEqualByComparingTo("843000");
        assertThat(r.descuentos()).isEqualByComparingTo("168600");
        assertThat(r.netoHaberes()).isEqualByComparingTo("674400");
        assertThat(r.noRemunerativo()).isEqualByComparingTo("440000");
        assertThat(r.netoTotal()).isEqualByComparingTo("1114400");
    }

    @Test
    void cadaConceptoDeHorasUsaSuFactor() {
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala("2026-01-01", "3000", "500000"),
                HORAS, Map.of());

        assertThat(importe(r, AL_50)).isEqualByComparingTo("45000");
        assertThat(importe(r, AL_100)).isEqualByComparingTo("36000");
        // 30 horas nocturnas son 240 minutos de hora normal: 4 horas.
        assertThat(importe(r, NOCTURNAS)).isEqualByComparingTo("12000");
    }

    @Test
    void siLaEscalaTraeElValorDeLaHoraSeUsaEseValor() {
        // La hoja del sindicato publica la hora al 50% y al 100%, que difieren unos centavos de 1,5 y 2 veces la normal.
        EscalaSueldo base = escala("2026-01-01", "3000", "500000");
        Map<Long, BigDecimal> valores = new java.util.HashMap<>(base.getValores());
        valores.put(AL_50.getId(), bd("4500.07"));
        valores.put(AL_100.getId(), bd("6000.11"));
        EscalaSueldo conHoras = new EscalaSueldo(base.getVigenteDesde(), base.getValorHora(), valores);

        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, conHoras, HORAS, Map.of());

        assertThat(importe(r, AL_50)).isEqualByComparingTo("45000.70");
        assertThat(importe(r, AL_100)).isEqualByComparingTo("36000.66");
        // Las nocturnas no tienen valor propio: siguen siendo la hora normal × 8/60.
        assertThat(importe(r, NOCTURNAS)).isEqualByComparingTo("12000");
        assertThat(r.lineas().stream().filter(l -> AL_50.getId().equals(l.conceptoId())).findFirst().orElseThrow()
                .valor()).isEqualByComparingTo("4500.07");
    }

    @Test
    void sinHorasSoloSumanLosFijos() {
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala("2026-01-01", "3000", "500000"),
                Map.of(), Map.of());

        assertThat(r.bruto()).isEqualByComparingTo("750000");
        assertThat(r.descuentos()).isEqualByComparingTo("150000");
        assertThat(r.netoTotal()).isEqualByComparingTo("1040000");
    }

    @Test
    void unaEscalaNuevaCambiaLosMontos() {
        CalculadoraSueldo.Resultado antes = CalculadoraSueldo.calcular(CONCEPTOS,
                escala("2026-01-01", "3000", "500000"), HORAS, Map.of());
        CalculadoraSueldo.Resultado despues = CalculadoraSueldo.calcular(CONCEPTOS,
                escala("2026-07-01", "3300", "550000"), HORAS, Map.of());

        // 550.000 + 100.000 + 90.000 + 60.000 + horas con la hora a 3.300 (49.500 + 39.600 + 13.200)
        assertThat(despues.bruto()).isEqualByComparingTo("902300");
        assertThat(despues.bruto()).isGreaterThan(antes.bruto());
        assertThat(despues.valorHora()).isEqualByComparingTo("3300");
    }

    @Test
    void unAjustePuntualPisaElImporteYLoMarca() {
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala("2026-01-01", "3000", "500000"),
                HORAS, Map.of(PRESENTISMO.getId(), bd("80000")));

        assertThat(r.bruto()).isEqualByComparingTo("823000");
        CalculadoraSueldo.Linea presentismo = linea(r, PRESENTISMO);
        assertThat(presentismo.ajustado()).isTrue();
        assertThat(presentismo.importe()).isEqualByComparingTo("80000");
        assertThat(linea(r, BASICO).ajustado()).isFalse();
        // Los descuentos se calculan sobre el bruto ajustado.
        assertThat(r.descuentos()).isEqualByComparingTo("164600");
    }

    @Test
    void redondeaACentavosEnCadaConcepto() {
        // 7 horas nocturnas a 3.001: 7 × 3.001 × 8 / 60 = 2.800,9333…
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala("2026-01-01", "3001", "500000"),
                Map.of(NOCTURNAS.getId(), bd("7")), Map.of());

        assertThat(importe(r, NOCTURNAS)).isEqualByComparingTo("2800.93");
    }

    @Test
    void lasLineasSalenEnElOrdenDeLosConceptos() {
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala("2026-01-01", "3000", "500000"),
                HORAS, Map.of());

        assertThat(r.lineas()).extracting(CalculadoraSueldo.Linea::conceptoId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);
    }

    @Test
    void unConceptoInactivoNoCuenta() {
        ConceptoSueldo inactivo = concepto(2, "Presentismo", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
        inactivo.editar("Presentismo", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null, 2, false);
        List<ConceptoSueldo> conceptos = CONCEPTOS.stream().map(c -> c.getId() == 2 ? inactivo : c).toList();

        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(conceptos, escala("2026-01-01", "3000", "500000"),
                HORAS, Map.of());

        assertThat(r.bruto()).isEqualByComparingTo("743000");
    }

    @Test
    void lasHorasNecesitanUnFactor() {
        assertThatThrownBy(() -> new ConceptoSueldo("Horas", TipoConceptoSueldo.HORAS, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal importe(CalculadoraSueldo.Resultado r, ConceptoSueldo concepto) {
        return linea(r, concepto).importe();
    }

    private static CalculadoraSueldo.Linea linea(CalculadoraSueldo.Resultado r, ConceptoSueldo concepto) {
        return r.lineas().stream().filter(l -> l.conceptoId().equals(concepto.getId())).findFirst().orElseThrow();
    }
}
