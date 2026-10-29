package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.TipoConceptoSueldo;

class ParserHorasSueldoTest {

    static final ConceptoSueldo AL_50 = horas(5, "Horas al 50%", 3, 2);
    static final ConceptoSueldo AL_100 = horas(6, "Horas al 100%", 2, 1);
    static final ConceptoSueldo NOCTURNAS = horas(7, "Horas nocturnas", 8, 60);
    static final List<ConceptoSueldo> HORAS = List.of(AL_50, AL_100, NOCTURNAS);

    private static ConceptoSueldo horas(long id, String nombre, int num, int den) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, TipoConceptoSueldo.HORAS, num, den, (int) id));
    }

    private static Map<Long, BigDecimal> parsear(String texto) {
        return ParserHorasSueldo.parsear(texto, HORAS).orElse(Map.of());
    }

    @Test
    void entiendeLaFraseDelPedido() {
        assertThat(parsear("este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas")).containsOnly(
                Map.entry(AL_50.getId(), new BigDecimal("10")), Map.entry(AL_100.getId(), new BigDecimal("6")),
                Map.entry(NOCTURNAS.getId(), new BigDecimal("30")));
    }

    @Test
    void sirvenOtrasFormasDeDecirlo() {
        assertThat(parsear("Hice 8 hs al 50% y 12 hs nocturnas")).containsOnly(
                Map.entry(AL_50.getId(), new BigDecimal("8")), Map.entry(NOCTURNAS.getId(), new BigDecimal("12")));
        assertThat(parsear("este mes trabajé 2,5 horas al 100")).containsOnly(
                Map.entry(AL_100.getId(), new BigDecimal("2.5")));
        assertThat(parsear("ESTE MES HICE 4 HORAS NOCTURNAS")).containsOnly(
                Map.entry(NOCTURNAS.getId(), new BigDecimal("4")));
    }

    @Test
    void elAl100NoSeConfundeConElAl50() {
        Map<Long, BigDecimal> horas = parsear("este mes hice 6 horas al 100");

        assertThat(horas).containsOnlyKeys(AL_100.getId());
    }

    @Test
    void unGastoQueMencionaHorasNoEsUnCalculo() {
        assertThat(ParserHorasSueldo.parsear("2 horas de estacionamiento 1500", HORAS)).isEmpty();
        assertThat(ParserHorasSueldo.parsear("pagué 50 al kiosco", HORAS)).isEmpty();
        assertThat(ParserHorasSueldo.parsear("super 8050", HORAS)).isEmpty();
        assertThat(ParserHorasSueldo.parsear("", HORAS)).isEmpty();
        assertThat(ParserHorasSueldo.parsear(null, HORAS)).isEmpty();
    }

    @Test
    void siDiceQueSonHorasPeroNoSeEntiendeNingunaNoHaceNada() {
        assertThat(ParserHorasSueldo.parsear("este mes hice muchas horas", HORAS)).isEmpty();
    }

    @Test
    void sinConceptosDeHorasNoHayNada() {
        assertThat(ParserHorasSueldo.parsear("este mes hice 10 horas al 50", List.of())).isEmpty();
    }

    @Test
    void losComandosPosicionalesSonNumeros() {
        assertThat(ParserHorasSueldo.posicionales("10 6 30")).contains(List.of(new BigDecimal("10"),
                new BigDecimal("6"), new BigDecimal("30")));
        assertThat(ParserHorasSueldo.posicionales("10,5, 0")).contains(List.of(new BigDecimal("10.5"),
                BigDecimal.ZERO));
        assertThat(ParserHorasSueldo.posicionales("10 seis")).isEqualTo(Optional.empty());
        assertThat(ParserHorasSueldo.posicionales("   ")).isEmpty();
        assertThat(ParserHorasSueldo.posicionales("-4")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void lasHorasQueNormalizoLaIaSeLeenSinPedirQueDigaEsteMes() {
        assertThat(ParserHorasSueldo.cantidades("10 al 50, 6 al 100, 30 nocturnas", HORAS)).hasValueSatisfying(h -> {
            assertThat(h.get(AL_50.getId())).isEqualByComparingTo("10");
            assertThat(h.get(AL_100.getId())).isEqualByComparingTo("6");
            assertThat(h.get(NOCTURNAS.getId())).isEqualByComparingTo("30");
        });
        assertThat(ParserHorasSueldo.parsear("10 al 50, 6 al 100", HORAS)).as("sin IA hace falta la intención")
                .isEmpty();
        assertThat(ParserHorasSueldo.cantidades("nada", HORAS)).isEmpty();
    }
}
