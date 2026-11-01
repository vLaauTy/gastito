package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.tarjeta.CicloTarjetaService;

class TarjetaProgramadaTest {

    private final CicloTarjetaService ciclo = mock(CicloTarjetaService.class);
    private final GastitoBot bot = mock(GastitoBot.class);
    private final TarjetaProgramada programada = new TarjetaProgramada(ciclo,
            new ConversacionTarjeta(ciclo, new FormatoMensajes(RELOJ)), bot);

    @BeforeEach
    void nadaPendiente() {
        when(ciclo.preguntaDeFechas()).thenReturn(Optional.empty());
        when(ciclo.avisoDeVencimiento()).thenReturn(Optional.empty());
        when(ciclo.preguntaDeDebito()).thenReturn(Optional.empty());
    }

    @Test
    void elAvisoQueLlegaQuedaAnotado() {
        when(ciclo.preguntaDeFechas()).thenReturn(Optional.of(ConversacionTarjetaTest.proximo()));
        when(ciclo.preguntaDeDebito()).thenReturn(Optional.of(ConversacionTarjetaTest.cerrado(null)));
        when(bot.enviarAlUsuario(any())).thenReturn(true);

        programada.revisar();

        verify(ciclo).preguntoFechas(ConversacionTarjetaTest.PROXIMO);
        verify(ciclo).preguntoDebito(ConversacionTarjetaTest.CERRADO);
        verify(ciclo, never()).avisoVencimientoEnviado(any());
    }

    @Test
    void siTelegramNoLoRecibeSeReintenta() {
        when(ciclo.avisoDeVencimiento()).thenReturn(Optional.of(ConversacionTarjetaTest.cerrado(null)));
        when(bot.enviarAlUsuario(any())).thenReturn(false);

        programada.revisar();

        verify(ciclo, never()).avisoVencimientoEnviado(any());
    }
}
