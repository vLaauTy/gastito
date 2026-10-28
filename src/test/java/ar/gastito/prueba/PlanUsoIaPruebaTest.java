package ar.gastito.prueba;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ar.gastito.ia.PropositoIa;

class PlanUsoIaPruebaTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 27, 15, 0);
    private final List<PlanUsoIaPrueba.Llamada> llamadas = PlanUsoIaPrueba.armar(AHORA, 3, 20261025L);

    @Test
    void cubreTresMesesHastaAhoraYNadaEnElFuturo() {
        assertThat(llamadas.getFirst().fechaHora().toLocalDate()).isAfterOrEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(llamadas.stream().map(l -> YearMonth.from(l.fechaHora())).distinct()).containsExactly(
                YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(llamadas).allSatisfy(l -> assertThat(l.fechaHora()).isBeforeOrEqualTo(AHORA));
        assertThat(llamadas).isSortedAccordingTo(java.util.Comparator.comparing(PlanUsoIaPrueba.Llamada::fechaHora));
    }

    @Test
    void usaTodosLosPropositos() {
        Map<PropositoIa, Long> porProposito = llamadas.stream()
                .collect(Collectors.groupingBy(PlanUsoIaPrueba.Llamada::proposito, Collectors.counting()));

        assertThat(porProposito.keySet()).containsExactlyInAnyOrder(PropositoIa.values());
        // Un recibo por mes, a lo sumo (el 4 de octubre ya pasó).
        assertThat(porProposito.get(PropositoIa.RECIBO)).isEqualTo(3);
    }

    @Test
    void losTokensSonPositivosYLosTicketsPesanMasQueUnTexto() {
        assertThat(llamadas).allSatisfy(l -> {
            assertThat(l.tokensEntrada()).isPositive();
            assertThat(l.tokensSalida()).isPositive();
        });
        double textos = promedioEntrada(PropositoIa.TEXTO);
        double tickets = promedioEntrada(PropositoIa.TICKET);
        assertThat(tickets).isGreaterThan(textos * 2);
    }

    @Test
    void cadaMesTieneUnaConsultaEnormeParaMirar() {
        for (int mes = 8; mes <= 10; mes++) {
            int m = mes;
            long maximo = llamadas.stream().filter(l -> l.fechaHora().getMonthValue() == m)
                    .mapToLong(PlanUsoIaPrueba.Llamada::tokensEntrada).max().orElseThrow();
            assertThat(maximo).as("mes %d", mes).isGreaterThanOrEqualTo(14000);
        }
    }

    @Test
    void conLaMismaSemillaSaleLoMismo() {
        assertThat(PlanUsoIaPrueba.armar(AHORA, 3, 20261025L)).isEqualTo(llamadas);
        assertThat(PlanUsoIaPrueba.armar(AHORA, 3, 1L)).isNotEqualTo(llamadas);
    }

    private double promedioEntrada(PropositoIa proposito) {
        return llamadas.stream().filter(l -> l.proposito() == proposito)
                .mapToLong(PlanUsoIaPrueba.Llamada::tokensEntrada).average().orElseThrow();
    }
}
