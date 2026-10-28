package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.recibo.ControlRecibo;
import ar.gastito.recibo.PdfInvalidoException;
import ar.gastito.recibo.ReciboService;
import ar.gastito.recibo.TipoDocumento;
import ar.gastito.recibo.TipoRecibo;
import ar.gastito.recibo.ValidadorRecibo;
import ar.gastito.sueldo.SueldoService;

class ConversacionRecibosTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    private final ReciboService recibos = mock(ReciboService.class);
    private final TarjetasRecibo tarjetas = new TarjetasRecibo(new FormatoMensajes(RELOJ), mock(SueldoService.class));
    private final ConversacionRecibos conversacion = new ConversacionRecibos(recibos, tarjetas);

    private static ReciboService.VistaRecibo vista() {
        return new ReciboService.VistaRecibo(7L, null, "Empresa Ejemplo", YearMonth.of(2026, 10),
                TipoRecibo.SUELDO_MENSUAL, LocalDate.of(2026, 11, 3), new BigDecimal("1250000"),
                new BigDecimal("212500"), new BigDecimal("1037500"), List.of(), null, true, null,
                new ControlRecibo.Resultado(List.of(), false, false));
    }

    private static PdfRecibido pdf() {
        return new PdfRecibido(10L, "el de octubre", () -> PDF);
    }

    private String texto(Accion accion) {
        return ((Accion.Enviar) accion).html();
    }

    @Test
    void seDetectaLaPalabraReciboEnElTextoDeLaFoto() {
        assertThat(ConversacionRecibos.mencionaRecibo("mi recibo")).isTrue();
        assertThat(ConversacionRecibos.mencionaRecibo("Recibo de sueldo")).isTrue();
        assertThat(ConversacionRecibos.mencionaRecibo("el aguinaldo")).isTrue();
        assertThat(ConversacionRecibos.mencionaRecibo("liquidación final")).isTrue();
        assertThat(ConversacionRecibos.mencionaRecibo("efectivo")).isFalse();
        assertThat(ConversacionRecibos.mencionaRecibo("con la de Galicia")).isFalse();
        assertThat(ConversacionRecibos.mencionaRecibo(null)).isFalse();
    }

    @Test
    void unReciboLeidoYaEstaGuardadoYSoloSePuedeDescartar() {
        when(recibos.leerPdf(PDF, "el de octubre")).thenReturn(new ReciboService.Leido(vista(), false));

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.leerPdf(pdf());

        assertThat(tarjeta.html()).startsWith("💼 <b>Recibo · Octubre 2026 · Empresa Ejemplo</b>");
        // No es la tarjeta de un gasto: no hay nada que guardar ni editar.
        assertThat(tarjeta.tarjetaDeMovimiento()).isNull();
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("dr:7");
    }

    @Test
    void unArchivoDemasiadoGrandeNoSeDescarga() {
        when(recibos.superaElTamanio(20_000_000L)).thenReturn(true);
        when(recibos.tamanioMaximo()).thenReturn(DataSize.ofMegabytes(10));

        Accion r = conversacion.leerPdf(new PdfRecibido(20_000_000L, null, () -> {
            throw new AssertionError("No tiene que descargarlo");
        }));

        assertThat(texto(r)).contains("pesa más de 10 MB");
    }

    @Test
    void siTelegramNoDejaDescargarPideMandarloDeNuevo() {
        Accion r = conversacion.leerPdf(new PdfRecibido(10L, null, () -> {
            throw new IOException("sin red");
        }));

        assertThat(texto(r)).startsWith("No pude descargar el archivo de Telegram");
    }

    @Test
    void siLaIaNoEstaPideMandarloDeNuevoYNoGuardaNada() {
        when(recibos.leerPdf(any(), any())).thenThrow(new IaNoDisponibleException("Falta configurar la clave", null));

        assertThat(texto(conversacion.leerPdf(pdf()))).contains("No pude leer el recibo")
                .contains("no guardé nada");
    }

    @Test
    void unPdfQueNoSePuedeAbrirSeDice() {
        when(recibos.leerPdf(any(), any())).thenThrow(new PdfInvalidoException("roto", null));

        assertThat(texto(conversacion.leerPdf(pdf()))).startsWith("No pude abrir ese PDF");
    }

    @Test
    void unTicketOOtraCosaNoSeGuardaComoRecibo() {
        when(recibos.leerPdf(any(), any())).thenReturn(new ReciboService.NoEsRecibo(TipoDocumento.TICKET));
        assertThat(texto(conversacion.leerPdf(pdf()))).contains("parece un ticket de compra");

        when(recibos.leerPdf(any(), any())).thenReturn(new ReciboService.NoEsRecibo(TipoDocumento.OTRO));
        assertThat(texto(conversacion.leerPdf(pdf()))).contains("no parece un recibo de sueldo");
    }

    @Test
    void sinNetoPideUnArchivoMasClaro() {
        when(recibos.leerPdf(any(), any())).thenReturn(new ReciboService.Ilegible(ValidadorRecibo.Motivo.SIN_NETO));

        assertThat(texto(conversacion.leerPdf(pdf()))).startsWith("No encontré el neto del recibo")
                .contains("No guardé nada").contains("más clara");
    }

    @Test
    void unReciboBorrosoOSinPeriodoTambienSePideDeNuevo() {
        when(recibos.leerPdf(any(), any())).thenReturn(new ReciboService.Ilegible(ValidadorRecibo.Motivo.BORROSO));
        assertThat(texto(conversacion.leerPdf(pdf()))).contains("No pude leer bien el recibo");

        when(recibos.leerPdf(any(), any())).thenReturn(new ReciboService.Ilegible(ValidadorRecibo.Motivo.SIN_PERIODO));
        assertThat(texto(conversacion.leerPdf(pdf()))).contains("de qué mes es el recibo");
    }

    // --- Descartar ---

    @Test
    void descartarBorraElReciboYAvisa() {
        List<Accion> acciones = conversacion.descartar(100, 7L);

        verify(recibos).descartar(7L);
        Accion.Editar editada = (Accion.Editar) acciones.get(0);
        assertThat(editada.mensajeId()).isEqualTo(100);
        assertThat(editada.html()).startsWith("🗑 <b>Recibo descartado</b>");
        assertThat(editada.teclado().isVacio()).isTrue();
        assertThat(acciones.get(1)).isEqualTo(new Accion.Avisar("Descartado"));
    }

    @Test
    void descartarUnReciboQueYaNoEstaSoloSacaLosBotones() {
        doThrow(new NoSuchElementException("no existe")).when(recibos).descartar(7L);

        List<Accion> acciones = conversacion.descartar(100, 7L);

        assertThat(acciones).containsExactly(new Accion.QuitarBotones(100), new Accion.Avisar("Ese recibo ya no está"));
    }
}
