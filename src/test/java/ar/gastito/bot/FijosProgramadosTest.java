package ar.gastito.bot;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.RecordatorioFijoService;
import ar.gastito.movimiento.Movimiento;

class FijosProgramadosTest {

    private final GastoFijoService fijos = mock(GastoFijoService.class);
    private final RecordatorioFijoService recordatorios = mock(RecordatorioFijoService.class);
    private final ConversacionFijos conversacion = mock(ConversacionFijos.class);
    private final GastitoBot bot = mock(GastitoBot.class);
    private final FijosProgramados programados = new FijosProgramados(fijos, recordatorios, conversacion, bot);

    @Test
    void preguntaPorCadaCobroNuevo() {
        Movimiento seguro = mock(Movimiento.class);
        Movimiento luz = mock(Movimiento.class);
        Accion.Enviar preguntaSeguro = Accion.Enviar.texto("seguro");
        Accion.Enviar preguntaLuz = Accion.Enviar.texto("luz");
        when(fijos.generarCobros()).thenReturn(List.of(seguro, luz));
        when(conversacion.preguntaDeCobro(seguro)).thenReturn(preguntaSeguro);
        when(conversacion.preguntaDeCobro(luz)).thenReturn(preguntaLuz);
        when(bot.enviarAlUsuario(List.of(preguntaSeguro, preguntaLuz))).thenReturn(true);

        programados.alArrancar();

        verify(bot).enviarAlUsuario(List.of(preguntaSeguro, preguntaLuz));
    }

    @Test
    void sinCobrosNuevosNoManda() {
        when(fijos.generarCobros()).thenReturn(List.of());

        programados.revisar();

        verify(bot, never()).enviarAlUsuario(anyList());
    }

    @Test
    void siFallaNoSeCaeLaAplicacion() {
        when(fijos.generarCobros()).thenThrow(new IllegalStateException("sin base"));

        programados.revisar();

        verify(bot, never()).enviarAlUsuario(anyList());
    }

    @Test
    void mandaElRecordatorioAgrupadoSiCorresponde() {
        when(fijos.generarCobros()).thenReturn(List.of());
        List<ar.gastito.fijo.RecordatorioFijoService.Vencido> vencidos =
                List.of(new ar.gastito.fijo.RecordatorioFijoService.Vencido(1L, "Luz", 10, new java.math.BigDecimal("85000")));
        when(recordatorios.siCorresponde()).thenReturn(java.util.Optional.of(vencidos));
        Accion.Enviar mensaje = Accion.Enviar.texto("recordatorio");
        when(conversacion.recordatorio(vencidos)).thenReturn(mensaje);
        when(bot.enviarAlUsuario(List.of(mensaje))).thenReturn(true);

        programados.revisar();

        verify(bot).enviarAlUsuario(List.of(mensaje));
    }

    @Test
    void siNoCorrespondeElRecordatorioNoMandaNada() {
        when(fijos.generarCobros()).thenReturn(List.of());
        when(recordatorios.siCorresponde()).thenReturn(java.util.Optional.empty());

        programados.revisar();

        verify(bot, never()).enviarAlUsuario(anyList());
    }
}
