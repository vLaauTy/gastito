package ar.gastito.fijo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.resumen.RelojDePrueba;

class RecordatorioFijoServiceTest {

    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 15, 10, 0);

    private final GastoFijoService fijos = mock(GastoFijoService.class);
    private final RecordatorioFijoRepository recordatorios = mock(RecordatorioFijoRepository.class);
    private final RelojDePrueba reloj = new RelojDePrueba(HOY, java.time.ZoneId.of("America/Argentina/Buenos_Aires"));
    private final RecordatorioFijoService servicio =
            new RecordatorioFijoService(fijos, recordatorios, new FijosProperties(Duration.ofDays(3)), reloj);

    private GastoFijoService.ItemChecklist vencido(String nombre, int dia) {
        return new GastoFijoService.ItemChecklist(1L, nombre, null, GastoFijoService.EstadoChecklist.FALTA, null,
                null, null, null, LocalDate.of(2026, 10, dia), false, null, false, new BigDecimal("28000"));
    }

    @Test
    void sinNadaVencidoNoHayRecordatorio() {
        when(fijos.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(vencido("Luz", 20)));

        assertThat(servicio.siCorresponde()).isEmpty();
    }

    @Test
    void conAlgoVencidoYSinRecordatorioPrevioCorresponde() {
        when(fijos.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(vencido("Luz", 10), vencido("Gas", 12)));
        when(recordatorios.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        Optional<List<RecordatorioFijoService.Vencido>> resultado = servicio.siCorresponde();
        assertThat(resultado).isPresent();
        assertThat(resultado.orElseThrow()).extracting(RecordatorioFijoService.Vencido::nombre)
                .containsExactly("Luz", "Gas");
        assertThat(resultado.orElseThrow().get(0).aproximado()).isEqualByComparingTo("28000");
    }

    @Test
    void siYaSeMandoHaceMenosDelIntervaloNoCorresponde() {
        when(fijos.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(vencido("Luz", 10)));
        when(recordatorios.findTopByOrderByIdDesc())
                .thenReturn(Optional.of(new RecordatorioFijo(HOY.minusDays(1))));

        assertThat(servicio.siCorresponde()).isEmpty();
    }

    @Test
    void siPasoElIntervaloVuelveACorresponder() {
        when(fijos.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(vencido("Luz", 10)));
        when(recordatorios.findTopByOrderByIdDesc())
                .thenReturn(Optional.of(new RecordatorioFijo(HOY.minusDays(4))));

        assertThat(servicio.siCorresponde()).isPresent();
    }
}
