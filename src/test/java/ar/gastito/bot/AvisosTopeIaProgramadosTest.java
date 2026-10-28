package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.ia.CostosIaService;
import ar.gastito.ia.TopeIaService;

class AvisosTopeIaProgramadosTest {

    static final TopeIaService.Aviso AVISO = new TopeIaService.Aviso(YearMonth.of(2026, 10), 80,
            new BigDecimal("1.62"), new BigDecimal("2"), 81);

    private final TopeIaService tope = mock(TopeIaService.class);
    private final GastitoBot bot = mock(GastitoBot.class);
    private final FormatoCostos formato = new FormatoCostos(mock(CostosIaService.class), new FormatoMensajes(RELOJ),
            RELOJ);
    private final AvisosTopeIaProgramados avisos = new AvisosTopeIaProgramados(tope, formato, bot);

    @Test
    void sinAvisoPendienteNoMandaNada() {
        when(tope.pendiente()).thenReturn(Optional.empty());

        avisos.revisar();

        verify(bot, never()).enviarAlUsuario(any());
    }

    @Test
    void mandaElAvisoYLoAnotaCuandoTelegramLoRecibe() {
        when(tope.pendiente()).thenReturn(Optional.of(AVISO));
        when(bot.enviarAlUsuario(any())).thenReturn(true);

        avisos.revisar();

        verify(bot).enviarAlUsuario(List.of(formato.aviso(AVISO)));
        verify(tope).marcarEnviado(AVISO);
    }

    @Test
    void siTelegramFallaNoLoAnotaYLoReintenta() {
        when(tope.pendiente()).thenReturn(Optional.of(AVISO));
        when(bot.enviarAlUsuario(any())).thenReturn(false);

        avisos.revisar();
        avisos.revisar();

        verify(bot, times(2)).enviarAlUsuario(any());
        verify(tope, never()).marcarEnviado(any());
    }

    @Test
    void unaFallaAlRevisarNoCortaLaTareaProgramada() {
        when(tope.pendiente()).thenThrow(new IllegalStateException("la base no responde"));

        avisos.revisar();

        verify(bot, never()).enviarAlUsuario(any());
    }
}
