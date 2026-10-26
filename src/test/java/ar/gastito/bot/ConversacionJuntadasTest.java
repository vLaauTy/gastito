package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SALIDAS;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.juntada.EstadoParticipante;
import ar.gastito.juntada.JuntadaInvalidaException;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

class ConversacionJuntadasTest {

    static final long CHAT = 42;
    static final int TARJETA = 100;
    static final long MARTIN = 1;
    static final long PABLO = 3;

    private final JuntadaService servicio = mock(JuntadaService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final Tarjetas tarjetas = new Tarjetas(formato);
    private final ConversacionJuntadas conversacion = new ConversacionJuntadas(servicio, movimientos, tarjetas,
            formato);

    /** El asado: 48.000 entre 6, Pablo ya pagó. */
    private VistaJuntada asado(boolean guardada) {
        return new VistaJuntada(ID, 9L, "Asado", "Salidas", AHORA, new BigDecimal("48000.00"),
                new BigDecimal("8000.00"), new BigDecimal("32000.00"), 6, "Mercado Pago", guardada, List.of(
                        new VistaJuntada.Persona(MARTIN, "Martín", new BigDecimal("8000.00"),
                                EstadoParticipante.PENDIENTE, false),
                        new VistaJuntada.Persona(2L, "Sofi", new BigDecimal("8000.00"), EstadoParticipante.PENDIENTE,
                                false),
                        new VistaJuntada.Persona(PABLO, "Pablo", new BigDecimal("8000.00"), EstadoParticipante.PAGADO,
                                false),
                        new VistaJuntada.Persona(4L, "Persona 5", new BigDecimal("8000.00"),
                                EstadoParticipante.PENDIENTE, false),
                        new VistaJuntada.Persona(5L, "Persona 6", new BigDecimal("8000.00"),
                                EstadoParticipante.PERDIDO, false)));
    }

    private Movimiento pendiente;

    @BeforeEach
    void setUp() {
        pendiente = conCategoria("48000", SALIDAS);
        pendiente.prepararJuntada(new BigDecimal("8000.00"));
        when(movimientos.buscar(ID)).thenReturn(pendiente);
        when(servicio.vista(ID)).thenReturn(Optional.of(asado(false)));
        tarjetas.usarJuntadas(servicio::vista);
    }

    private static List<String> datos(Teclado t) {
        return t.botones().stream().map(Teclado.Boton::datos).toList();
    }

    @Test
    void laTarjetaDiceLoQuePagasteTuGastoYLoQueFaltaCobrar() {
        String html = tarjetas.pendiente(pendiente);

        assertThat(html).contains("💸 Pagaste <b>$48.000</b> · Mercado Pago\n🙋 Tu gasto real <b>$8.000</b>\n"
                + "📥 A cobrar <b>$32.000</b>")
                .contains("👥 <b>Entre 6</b> (vos $8.000)\n• Martín: $8.000 ⏳\n• Sofi: $8.000 ⏳\n• Pablo: $8.000 ✅")
                .contains("• Persona 6: $8.000 🚫 perdido")
                .doesNotContain("💰");
        assertThat(datos(tarjetas.principal(pendiente))).contains("g:15", "jn:15", "jp:15");
    }

    @Test
    void guardadaOfreceArmarElMensajeYDarPorPerdido() {
        assertThat(datos(tarjetas.juntadaGuardada(asado(true)))).containsExactly("jm:15", "jn:15", "jp:15", "jx:15");
    }

    @Test
    void cambiarElNombreDeAlguien() {
        List<Accion> elegir = conversacion.boton(CHAT, TARJETA, Tarjetas.JUNTADA_NOMBRES, ID, null);
        Accion.Editar lista = (Accion.Editar) elegir.get(0);
        assertThat(lista.html()).endsWith("👥 ¿A quién le cambiás el nombre?");
        // Para el nombre se puede elegir a cualquiera (también a los que ya pagaron).
        assertThat(datos(lista.teclado())).contains("jn:15:1", "jn:15:3", "jv:15");

        conversacion.boton(CHAT, TARJETA, Tarjetas.JUNTADA_NOMBRES, ID, PABLO);
        assertThat(conversacion.esperando(CHAT)).isTrue();

        List<Accion> acciones = conversacion.texto(CHAT, "Pablito");

        verify(servicio).renombrar(PABLO, "Pablito");
        assertThat(conversacion.esperando(CHAT)).isFalse();
        assertThat(((Accion.Editar) acciones.get(0)).mensajeId()).isEqualTo(TARJETA);
    }

    @Test
    void laParteDeAlguienSoloEntreLosQueDebenYConUnMontoQueSirva() {
        Accion.Editar lista = (Accion.Editar) conversacion.boton(CHAT, TARJETA, Tarjetas.JUNTADA_PARTE, ID, null)
                .get(0);
        assertThat(datos(lista.teclado())).containsExactly("jp:15:1", "jp:15:2", "jp:15:4", "jv:15");

        conversacion.boton(CHAT, TARJETA, Tarjetas.JUNTADA_PARTE, ID, MARTIN);
        assertThat(conversacion.texto(CHAT, "no sé")).singleElement().isInstanceOf(Accion.Avisar.class);
        when(servicio.cambiarParte(MARTIN, new BigDecimal("60000")))
                .thenThrow(new JuntadaInvalidaException("No queda nada para tu parte"));
        assertThat(((Accion.Enviar) conversacion.texto(CHAT, "60.000").get(0)).html())
                .contains("No queda nada para tu parte");
        assertThat(conversacion.esperando(CHAT)).isTrue();

        conversacion.texto(CHAT, "5000 pesos");

        verify(servicio).cambiarParte(MARTIN, new BigDecimal("5000"));
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void darPorPerdidoPasaLaParteAGastoMio() {
        List<Accion> acciones = conversacion.boton(CHAT, TARJETA, Tarjetas.JUNTADA_PERDIDO, ID, MARTIN);

        verify(servicio).darPorPerdido(MARTIN);
        assertThat(acciones).contains(new Accion.Avisar("🚫 La parte de Martín pasó a ser gasto tuyo"));
    }

    @Test
    void elMensajeParaElGrupoTieneSoloALosQueDeben() {
        String html = conversacion.mensajeParaElGrupo(asado(true));

        assertThat(html).startsWith("💬 Copiá esto y mandalo al grupo:\n\n<pre>")
                .contains("Les paso lo de asado del martes 13/10: fueron $48.000 entre 6, $8.000 cada uno.")
                .contains("• Martín: $8.000\n• Sofi: $8.000\n• Persona 5: $8.000\n\nMe lo pueden pasar a Mercado Pago.")
                .doesNotContain("Pablo").doesNotContain("Persona 6");
    }

    // --- Devoluciones ---

    private static ValidadorInterpretacion.Devolucion pablo(String monto) {
        return new ValidadorInterpretacion.Devolucion(monto == null ? null : new BigDecimal(monto), "Pablo", null,
                null, null, AHORA);
    }

    @Test
    void unaDevolucionDeUnSoloDeudorSeGuardaConUnToque() {
        JuntadaService.Deudor martin = new JuntadaService.Deudor(MARTIN, "Martín", new BigDecimal("8000.00"),
                asado(true));
        when(servicio.deudores("Pablo", null, new BigDecimal("8000"))).thenReturn(List.of(martin));
        Movimiento devolucion = conCategoria("8000", null);
        when(servicio.devolucionPendiente(any(), any(), any(), eq(AHORA), eq(Origen.TEXTO), eq("x")))
                .thenReturn(devolucion);

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.devolucion(pablo("8000"), Origen.TEXTO, "x", "");

        assertThat(tarjeta.html()).startsWith("↩️ <b>Te devolvieron plata</b>\n💰 <b>$8.000</b> · Pablo")
                .contains("Martín, de Asado del martes 13/10 (te debía $8.000)");
        assertThat(datos(tarjeta.teclado())).containsExactly("jd:15:1", "c:15");
    }

    @Test
    void siHayVariasPosiblesPreguntaDeQuienEs() {
        when(servicio.deudores(any(), any(), any())).thenReturn(List.of(
                new JuntadaService.Deudor(MARTIN, "Martín", new BigDecimal("8000.00"), asado(true)),
                new JuntadaService.Deudor(2L, "Sofi", new BigDecimal("8000.00"), asado(true))));
        when(servicio.devolucionPendiente(any(), any(), any(), any(), any(), any()))
                .thenReturn(conCategoria("8000", null));

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.devolucion(pablo("8000"), Origen.TEXTO, "x", "");

        assertThat(tarjeta.html()).endsWith("¿De quién es?");
        assertThat(datos(tarjeta.teclado())).containsExactly("jd:15:1", "jd:15:2", "c:15");
    }

    @Test
    void sinJuntadasConDeudasOSinMontoNoSeGuardaNada() {
        when(servicio.deudores(any(), any(), any())).thenReturn(List.of());

        assertThat(((Accion.Enviar) conversacion.devolucion(pablo("8000"), Origen.TEXTO, "x", "")).html())
                .contains("no tenés juntadas con plata a cobrar");
        assertThat(((Accion.Enviar) conversacion.devolucion(pablo(null), Origen.TEXTO, "x", "")).html())
                .contains("¿Cuánto te devolvieron?");
        verify(servicio, never()).devolucionPendiente(any(), any(), any(), any(), any(), any());
    }

    @Test
    void alCobrarDiceCuantoFaltaYQueNoEsUnIngreso() {
        Movimiento devolucion = mock(Movimiento.class);
        when(devolucion.getMonto()).thenReturn(new BigDecimal("8000.00"));
        when(devolucion.getDescripcion()).thenReturn("Martín, de Asado");
        when(servicio.cobrar(ID, MARTIN)).thenReturn(new JuntadaService.Cobro(devolucion, asado(true),
                new BigDecimal("8000.00")));

        Accion.Editar editar = (Accion.Editar) conversacion.boton(CHAT, TARJETA, Tarjetas.DEVOLUCION_DE, ID, MARTIN)
                .get(0);

        assertThat(editar.html()).isEqualTo("""
                ✅ <b>Cobrado</b>
                Martín, de Asado: $8.000
                📥 De Asado te falta cobrar <b>$32.000</b>.

                <i>Es una devolución: no cuenta como ingreso.</i>""");
    }
}
