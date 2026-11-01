package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.tarjeta.CalendarioTarjeta;
import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.EstadoResumenTarjeta;
import ar.gastito.tarjeta.VistaResumenTarjeta;

class ConversacionTarjetaTest {

    static final long CHAT = 42;
    static final int MENSAJE = 100;
    static final long CERRADO = 7;
    static final long PROXIMO = 8;

    private final CicloTarjetaService ciclo = mock(CicloTarjetaService.class);
    private final ConversacionTarjeta conversacion = new ConversacionTarjeta(ciclo, new FormatoMensajes(RELOJ));

    /** El que cerró el 29/10 y vence el 11/11, con $200.000 cargados. */
    static VistaResumenTarjeta cerrado(String totalReal) {
        BigDecimal real = totalReal == null ? null : new BigDecimal(totalReal);
        return new VistaResumenTarjeta(CERRADO, YearMonth.of(2026, 10), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11), false, EstadoResumenTarjeta.CERRADO,
                new BigDecimal("180000"), new BigDecimal("20000"), new BigDecimal("200000"), real,
                real == null ? null : real.subtract(new BigDecimal("200000")), "MasterCard Gold", "Galicia",
                List.of());
    }

    /** El próximo, estimado: cierra ≈ 30/11 y vence ≈ 14/12. */
    static VistaResumenTarjeta proximo() {
        return new VistaResumenTarjeta(PROXIMO, YearMonth.of(2026, 11), LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 14), true, EstadoResumenTarjeta.ABIERTO,
                new BigDecimal("2500"), new BigDecimal("10000"), new BigDecimal("12500"), null, null,
                "MasterCard Gold", "Galicia", List.of());
    }

    private void conPanel() {
        when(ciclo.panel()).thenReturn(Optional.of(new CicloTarjetaService.Panel("MasterCard Gold", "Galicia",
                List.of(cerrado(null)), proximo(), List.of(), List.of())));
    }

    /** El texto del último mensaje enviado o editado. */
    private static String texto(List<Accion> acciones) {
        return acciones.reversed().stream().<String>mapMulti((a, salida) -> {
            if (a instanceof Accion.Enviar e) {
                salida.accept(e.html());
            } else if (a instanceof Accion.Editar e) {
                salida.accept(e.html());
            }
        }).findFirst().orElseThrow(() -> new AssertionError("No hay ningún mensaje: " + acciones));
    }

    // --- Cómo viene ---

    @Test
    void sinCicloPideElUltimoCierre() {
        when(ciclo.panel()).thenReturn(Optional.empty());

        assertThat(texto(conversacion.estado())).contains("Todavía no sé cuándo cierra la tarjeta");
    }

    @Test
    void elEstadoMuestraElCerradoPorPagarYElProximoConFechasEstimadas() {
        conPanel();

        String texto = texto(conversacion.estado());

        assertThat(texto).contains("Vence el 11/11 · según lo cargado <b>$200.000</b>");
        assertThat(texto).contains("Cierra ≈ 30/11 · vence ≈ 14/12");
        assertThat(texto).contains("Llevás <b>$12.500</b> (compras $2.500 + cuotas $10.000)");
        assertThat(texto).contains("≈ es una estimación");
    }

    // --- Fechas ---

    @Test
    void conLasDosFechasLasGuarda() {
        VistaResumenTarjeta confirmado = new VistaResumenTarjeta(PROXIMO, YearMonth.of(2026, 11),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11), false,
                EstadoResumenTarjeta.ABIERTO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                "MasterCard Gold", "Galicia", List.of());
        when(ciclo.cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11))).thenReturn(
                new CicloTarjetaService.FechasCargadas(confirmado, new CalendarioTarjeta.Fechas(
                        YearMonth.of(2026, 11), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 14), false)));

        String texto = texto(conversacion.fechas(CHAT, new ValidadorInterpretacion.FechasTarjeta(
                LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11)), ""));

        assertThat(texto).contains("cierra el 26/11 y vence el 11/12");
        assertThat(texto).contains("Lo tenía estimado para el ≈ 30/11");
    }

    @Test
    void siFaltaElVencimientoLoPreguntaYDespuesGuarda() {
        when(ciclo.cargarFechas(any(), any())).thenReturn(new CicloTarjetaService.FechasCargadas(proximo(), null));

        String pregunta = texto(conversacion.fechas(CHAT,
                new ValidadorInterpretacion.FechasTarjeta(LocalDate.of(2026, 11, 26), null), ""));
        assertThat(pregunta).contains("¿Y cuándo vence?");
        assertThat(conversacion.esperando(CHAT)).isTrue();

        assertThat(conversacion.texto(CHAT, "el 11")).isPresent();
        verify(ciclo).cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void siLoQueEscribeNoEsUnaFechaSeProcesaNormal() {
        conversacion.fechas(CHAT, new ValidadorInterpretacion.FechasTarjeta(LocalDate.of(2026, 11, 26), null), "");

        assertThat(conversacion.texto(CHAT, "kiosco 1500")).isEmpty();
        verify(ciclo, never()).cargarFechas(any(), any());
    }

    @Test
    void elRecordatorioDeFechasConTodaviaNoVuelveEnTresDias() {
        Accion.Enviar pregunta = conversacion.preguntaDeFechas(proximo());
        assertThat(pregunta.html()).contains("¿Ya sabés el próximo cierre y vencimiento de la tarjeta?");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("tf:8");
        when(ciclo.posponerFechas(PROXIMO)).thenReturn(LocalDate.of(2026, 11, 3));

        String respuesta = texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.TODAVIA_NO_FECHAS, PROXIMO,
                null));

        assertThat(respuesta).contains("Te vuelvo a preguntar el 03/11");
    }

    // --- Vencimiento, total y débito ---

    @Test
    void elAvisoDelVencimientoCargaElTotalDelResumen() {
        Accion.Enviar aviso = conversacion.avisoDeVencimiento(cerrado(null));
        assertThat(aviso.html()).isEqualTo(
                "💳 El 11/11 vence la tarjeta: según lo cargado, <b>$200.000</b>. Se debita de Galicia.");
        assertThat(aviso.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🧾 Cargar total del resumen");
        when(ciclo.cargarTotal(CERRADO, new BigDecimal("250000"))).thenReturn(new CicloTarjetaService.TotalCargado(
                cerrado("250000"), new BigDecimal("250000"), new BigDecimal("200000"), new BigDecimal("50000")));

        conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.CARGAR_TOTAL, CERRADO, null);
        String respuesta = texto(conversacion.texto(CHAT, "250.000").orElseThrow());

        assertThat(respuesta).contains("El resumen dice $250.000 y cargaste $200.000: faltan <b>$50.000</b> sin cargar.");
    }

    @Test
    void unTotalQueCoincideLoDice() {
        when(ciclo.cargarTotal(null, new BigDecimal("200000"))).thenReturn(new CicloTarjetaService.TotalCargado(
                cerrado("200000"), new BigDecimal("200000"), new BigDecimal("200000"), BigDecimal.ZERO));

        assertThat(texto(conversacion.total(new BigDecimal("200000"), null, ""))).contains("Coincide");
    }

    @Test
    void elDiaDelVencimientoConSiRegistraElPago() {
        Accion.Enviar pregunta = conversacion.preguntaDeDebito(cerrado("250000"));
        assertThat(pregunta.html()).contains("¿Se debitó el resumen de la tarjeta (<b>$250.000</b>)?");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("ts:7", "tn:7");
        when(ciclo.pagar(CERRADO, null, Origen.AUTOMATICO, null)).thenReturn(new CicloTarjetaService.Pago(
                Movimiento.pagoTarjeta(new BigDecimal("250000"), "Resumen", null, null, DatosDePrueba.AHORA,
                        Origen.AUTOMATICO, null, DatosDePrueba.AHORA), cerrado("250000")));

        String respuesta = texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.DEBITADO, CERRADO, null));

        assertThat(respuesta).contains("Débito registrado</b>: $250.000 desde Galicia");
        assertThat(respuesta).contains("No cuenta como gasto");
    }

    @Test
    void todaviaNoSeDebitoNoRegistraNada() {
        texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.TODAVIA_NO_DEBITO, CERRADO, null));

        verify(ciclo, never()).pagar(any(), any(), any(), any());
    }

    @Test
    void elDebitoDichoEnUnMensajeSeConfirmaConUnBoton() {
        conPanel();

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.pago(new BigDecimal("250000"), "").get(0);

        assertThat(tarjeta.html()).contains("¿Registro el débito del resumen que vence el 11/11?");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("tc:7:25000000", "tx:7");
        verify(ciclo, never()).pagar(any(), any(), any(), any());

        when(ciclo.pagar(eq(CERRADO), eq(new BigDecimal("250000.00")), eq(Origen.TEXTO), isNull())).thenReturn(
                new CicloTarjetaService.Pago(Movimiento.pagoTarjeta(new BigDecimal("250000"), "Resumen", null, null,
                        DatosDePrueba.AHORA, Origen.TEXTO, null, DatosDePrueba.AHORA), cerrado("250000")));
        assertThat(texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.CONFIRMAR_PAGO, CERRADO, 25000000L)))
                .contains("Débito registrado");
    }

    @Test
    void sinResumenCerradoNoHayDebitoParaRegistrar() {
        when(ciclo.panel()).thenReturn(Optional.empty());

        assertThat(texto(conversacion.pago(null, ""))).contains("No tengo ningún resumen cerrado sin debitar");
    }
}
