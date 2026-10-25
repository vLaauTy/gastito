package ar.gastito.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.resumen.PeriodoResumen;
import ar.gastito.resumen.RelojDePrueba;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;

class ResumenesProgramadosTest {

    static final LocalDateTime DOMINGO = LocalDateTime.of(2026, 10, 25, 21, 0);

    private final ResumenService resumenes = mock(ResumenService.class);
    private final GastitoBot bot = mock(GastitoBot.class);
    private final RelojDePrueba reloj = new RelojDePrueba(DOMINGO, DatosDePrueba.ARGENTINA);
    private final FormatoMensajes formato = new FormatoMensajes(reloj);
    private final ResumenesProgramados programados = new ResumenesProgramados(resumenes,
            new FormatoResumen(formato, new GraficoMes(formato), new DashboardProperties("http://localhost:8090", null)),
            bot, reloj);

    private final PeriodoResumen semana = PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 19));
    private final ResumenService.Envio envio = new ResumenService.Envio(semana,
            List.of(PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 12))));
    private final ResumenService.Armado armado = ResumenesDePrueba.vacio(TipoResumen.SEMANAL, semana.desde());

    @Test
    void mandaElPendienteConVerAnterioresYLoRegistra() {
        when(resumenes.pendientes(DOMINGO)).thenReturn(List.of(envio));
        when(resumenes.armar(semana, true)).thenReturn(armado);
        when(bot.enviarAlUsuario(anyList())).thenReturn(true);

        programados.revisar();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Accion.Enviar>> enviados = ArgumentCaptor.forClass(List.class);
        verify(bot).enviarAlUsuario(enviados.capture());
        Accion.Enviar mensaje = enviados.getValue().get(0);
        assertThat(mensaje.html()).contains("📬 Tenés 1 resumen anterior sin ver.");
        assertThat(mensaje.teclado().botones()).singleElement()
                .satisfies(b -> assertThat(b.datos()).isEqualTo("va:S:20261012:20261012"));
        verify(resumenes).registrarEnvio(envio, armado.resumen());
    }

    @Test
    void siTelegramFallaNoLoRegistraYEsperaAntesDeReintentar() {
        when(resumenes.pendientes(any())).thenReturn(List.of(envio));
        when(resumenes.armar(semana, true)).thenReturn(armado);
        when(bot.enviarAlUsuario(anyList())).thenReturn(false);

        programados.revisar();
        reloj.ir(DOMINGO.plusMinutes(5));
        programados.revisar(); // dentro de los 15 minutos: no se intenta
        reloj.ir(DOMINGO.plusMinutes(15));
        programados.revisar();

        verify(resumenes, never()).registrarEnvio(any(), any());
        verify(resumenes, times(2)).armar(semana, true);
    }

    @Test
    void sinPendientesNoMandaNada() {
        when(resumenes.pendientes(eq(DOMINGO))).thenReturn(List.of());

        programados.alArrancar();

        verify(bot, never()).enviarAlUsuario(anyList());
    }
}
