package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.tarjeta.CicloTarjetaService;

class TarjetaProgramadaTest {

    private final CicloTarjetaService ciclo = mock(CicloTarjetaService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final GastitoBot bot = mock(GastitoBot.class);
    private final TarjetaProgramada programada = new TarjetaProgramada(ciclo,
            new ConversacionTarjeta(ciclo, catalogos, new FormatoMensajes(RELOJ)), bot);

    @BeforeEach
    void nadaPendiente() {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(), List.of(), List.of(),
                List.of(ConversacionTarjetaTest.MASTERCARD, ConversacionTarjetaTest.TARJETA_MP)));
        when(ciclo.preguntasDeFechas()).thenReturn(List.of());
        when(ciclo.avisosDeVencimiento()).thenReturn(List.of());
        when(ciclo.preguntasDeDebito()).thenReturn(List.of());
    }

    @Test
    void elAvisoQueLlegaQuedaAnotado() {
        when(ciclo.preguntasDeFechas()).thenReturn(List.of(new CicloTarjetaService.PreguntaFechas(
                ConversacionTarjetaTest.GOLD, "MasterCard Gold", ConversacionTarjetaTest.proximo())));
        when(ciclo.preguntasDeDebito()).thenReturn(List.of(ConversacionTarjetaTest.cerrado(null)));
        when(bot.enviarAlUsuario(any())).thenReturn(true);

        programada.revisar();

        verify(ciclo).preguntoFechas(ConversacionTarjetaTest.PROXIMO);
        verify(ciclo).preguntoDebito(ConversacionTarjetaTest.CERRADO);
        verify(ciclo, never()).avisoVencimientoEnviado(any());
    }

    @Test
    void cadaTarjetaTieneSuAvisoYLaQueNoTieneFechasSeAnotaEnLaTarjeta() {
        when(ciclo.preguntasDeFechas()).thenReturn(List.of(
                new CicloTarjetaService.PreguntaFechas(ConversacionTarjetaTest.GOLD, "MasterCard Gold",
                        ConversacionTarjetaTest.proximo()),
                new CicloTarjetaService.PreguntaFechas(ConversacionTarjetaTest.MP, "Mercado Pago", null)));
        when(bot.enviarAlUsuario(any())).thenReturn(true);

        programada.revisar();

        verify(bot, times(2)).enviarAlUsuario(any());
        verify(ciclo).preguntoFechas(ConversacionTarjetaTest.PROXIMO);
        verify(ciclo).preguntoFechas(-ConversacionTarjetaTest.MP);
    }

    @Test
    void siTelegramNoLoRecibeSeReintenta() {
        when(ciclo.avisosDeVencimiento()).thenReturn(List.of(ConversacionTarjetaTest.cerrado(null)));
        when(bot.enviarAlUsuario(any())).thenReturn(false);

        programada.revisar();

        verify(ciclo, never()).avisoVencimientoEnviado(any());
        verify(ciclo, never()).preguntoFechas(anyLong());
    }
}
