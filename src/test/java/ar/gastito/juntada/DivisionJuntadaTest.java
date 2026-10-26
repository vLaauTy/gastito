package ar.gastito.juntada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class DivisionJuntadaTest {

    private static BigDecimal pesos(String monto) {
        return new BigDecimal(monto);
    }

    private static List<String> textos(List<BigDecimal> montos) {
        return montos.stream().map(BigDecimal::toPlainString).toList();
    }

    @Test
    void elAsadoEntreSeis() {
        DivisionJuntada.Reparto r = DivisionJuntada.enPartesIguales(pesos("48000"), 6);

        assertThat(r.miParte()).isEqualByComparingTo("8000");
        assertThat(textos(r.otras())).containsExactly("8000.00", "8000.00", "8000.00", "8000.00", "8000.00");
    }

    @Test
    void elRedondeoEsAPesoYLaDiferenciaMeLaQuedoYo() {
        DivisionJuntada.Reparto r = DivisionJuntada.enPartesIguales(pesos("50000"), 6);

        // 50.000 / 6 = 8.333,33: los demás 8.333, y yo 50.000 − 5 × 8.333 = 8.335.
        assertThat(textos(r.otras())).containsOnly("8333.00");
        assertThat(r.miParte()).isEqualByComparingTo("8335");
        assertThat(r.miParte().add(r.otras().stream().reduce(BigDecimal.ZERO, BigDecimal::add)))
                .isEqualByComparingTo("50000");
    }

    @Test
    void conCentavosLaDiferenciaTambienEsMia() {
        DivisionJuntada.Reparto r = DivisionJuntada.enPartesIguales(pesos("10000.50"), 3);

        assertThat(textos(r.otras())).containsOnly("3333.00");
        assertThat(r.miParte()).isEqualByComparingTo("3334.50");
    }

    @Test
    void unaParteDistintaYLasDemasSeAcomodanParaQueElTotalCierre() {
        // Martín puso 5000: los otros 43.000 entre 5 (yo y 4 más) = 8.600.
        List<DivisionJuntada.Parte> partes = List.of(
                new DivisionJuntada.Parte(pesos("5000"), true),
                new DivisionJuntada.Parte(pesos("8000"), false),
                new DivisionJuntada.Parte(pesos("8000"), false),
                new DivisionJuntada.Parte(pesos("8000"), false),
                new DivisionJuntada.Parte(pesos("8000"), false));

        DivisionJuntada.Reparto r = DivisionJuntada.repartir(pesos("48000"), partes);

        assertThat(textos(r.otras())).containsExactly("5000.00", "8600.00", "8600.00", "8600.00", "8600.00");
        assertThat(r.miParte()).isEqualByComparingTo("8600");
    }

    @Test
    void siTodosLosDemasEstanFijosElRestoEsMio() {
        DivisionJuntada.Reparto r = DivisionJuntada.repartir(pesos("30000"), List.of(
                new DivisionJuntada.Parte(pesos("10000"), true), new DivisionJuntada.Parte(pesos("12000"), true)));

        assertThat(r.miParte()).isEqualByComparingTo("8000");
    }

    @Test
    void siLasPartesFijasSumanElTotalNoQuedaNadaParaMi() {
        assertThatThrownBy(() -> DivisionJuntada.repartir(pesos("20000"), List.of(
                new DivisionJuntada.Parte(pesos("12000"), true), new DivisionJuntada.Parte(pesos("8000"), true))))
                .isInstanceOf(JuntadaInvalidaException.class).hasMessageContaining("no queda nada para tu parte");
        assertThatThrownBy(() -> DivisionJuntada.enPartesIguales(pesos("1000"), 1))
                .isInstanceOf(JuntadaInvalidaException.class);
    }
}
