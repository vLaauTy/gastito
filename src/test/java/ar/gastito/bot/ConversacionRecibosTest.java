package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.recibo.PdfInvalidoException;
import ar.gastito.recibo.ReciboService;
import ar.gastito.recibo.TipoDocumento;
import ar.gastito.recibo.ValidadorRecibo;

class ConversacionRecibosTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    private final ReciboService recibos = mock(ReciboService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final Tarjetas tarjetas = new Tarjetas(new FormatoMensajes(RELOJ));
    private final ConversacionRecibos conversacion = new ConversacionRecibos(recibos, movimientos, tarjetas);

    private static Movimiento ingreso(Origen origen, String monto) {
        return conId(Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal(monto), "Sueldo octubre 2026", null, null,
                AHORA, origen, null, AHORA), ID);
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
    void unReciboLeidoQuedaComoTarjetaConGuardarEditarYCancelar() {
        Movimiento m = ingreso(Origen.RECIBO, "1037500");
        when(recibos.leerPdf(PDF, "el de octubre")).thenReturn(new ReciboService.Leido(m));

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.leerPdf(pdf());

        assertThat(tarjeta.tarjetaDeMovimiento()).isEqualTo(ID);
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("g:15", "e:15", "c:15");
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
    void siLaIaNoEstaAvisaYOfreceCargarElSueldoAMano() {
        when(recibos.leerPdf(any(), any())).thenThrow(new IaNoDisponibleException("Falta configurar la clave", null));

        assertThat(texto(conversacion.leerPdf(pdf()))).contains("No pude leer el recibo")
                .contains("/sueldo 1.037.500 galicia");
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

    // --- Confirmación ---

    @Test
    void alGuardarConSueldoCargadoPreguntaSiLoReemplaza() {
        Movimiento nuevo = ingreso(Origen.RECIBO, "1037500");
        Movimiento anterior = ingreso(Origen.TEXTO, "1030000");
        when(movimientos.buscar(ID)).thenReturn(nuevo);
        when(recibos.yaCargado(ID)).thenReturn(Optional.of(anterior));

        List<Accion> acciones = conversacion.preguntarSiYaHay(100, ID).orElseThrow();

        Accion.Editar pregunta = (Accion.Editar) acciones.get(0);
        assertThat(pregunta.mensajeId()).isEqualTo(100);
        assertThat(pregunta.html()).contains("Ya cargaste el sueldo de octubre 2026 ($1.030.000). ¿Lo reemplazo con el "
                + "del recibo?");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("rr:15", "c:15");
    }

    @Test
    void sinNadaQueReemplazarSeGuardaNormal() {
        when(movimientos.buscar(ID)).thenReturn(ingreso(Origen.RECIBO, "1037500"));
        when(recibos.yaCargado(ID)).thenReturn(Optional.empty());

        assertThat(conversacion.preguntarSiYaHay(100, ID)).isEmpty();
    }

    @Test
    void unIngresoQueNoEsDeUnReciboNuncaPregunta() {
        when(movimientos.buscar(ID)).thenReturn(ingreso(Origen.TEXTO, "380000"));

        assertThat(conversacion.preguntarSiYaHay(100, ID)).isEmpty();
        verify(recibos, never()).yaCargado(any());
    }

    @Test
    void reemplazarMuestraElGuardadoYAvisa() {
        Movimiento m = ingreso(Origen.RECIBO, "1037500");
        when(recibos.confirmarReemplazando(ID)).thenReturn(
                new MovimientoService.Confirmacion(m, new BigDecimal("1037500")));

        List<Accion> acciones = conversacion.reemplazar(100, ID);

        assertThat(((Accion.Editar) acciones.get(0)).html()).startsWith("✅ <b>Guardado</b>");
        assertThat(((Accion.Editar) acciones.get(0)).teclado().isVacio()).isTrue();
        assertThat(acciones.get(1)).isEqualTo(new Accion.Avisar("✅ Guardado, reemplacé el anterior"));
    }

    @Test
    void soloUnReciboCanceladoSeDescarta() {
        conversacion.alCancelar(ingreso(Origen.TEXTO, "380000"));
        verify(recibos, never()).descartar(any());

        conversacion.alCancelar(ingreso(Origen.RECIBO, "1037500"));
        verify(recibos).descartar(ID);
    }
}
