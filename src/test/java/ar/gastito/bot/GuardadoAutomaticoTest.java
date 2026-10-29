package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.CATEGORIAS;
import static ar.gastito.bot.DatosDePrueba.DEBITO;
import static ar.gastito.bot.DatosDePrueba.EFECTIVO;
import static ar.gastito.bot.DatosDePrueba.FECHA_TICKET;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.KIOSCO;
import static ar.gastito.bot.DatosDePrueba.MEDIOS;
import static ar.gastito.bot.DatosDePrueba.PRODUCTOS;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SUPER;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static ar.gastito.bot.DatosDePrueba.conId;
import static ar.gastito.bot.DatosDePrueba.dudoso;
import static ar.gastito.bot.DatosDePrueba.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.audio.TranscripcionService;
import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.consulta.ConsultaService;
import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.ParserIngreso;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.ticket.TicketService;

/** Los gastos claros se guardan solos; los dudosos, repetidos o especiales se siguen confirmando. */
class GuardadoAutomaticoTest {

    static final long CHAT = 42;
    static final int TARJETA = 100;
    static final int MENSAJE_USUARIO = 101;
    static final BigDecimal TOPE = new BigDecimal("100000");

    private final InterpretadorService interpretador = mock(InterpretadorService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final TicketService tickets = mock(TicketService.class);
    private final ConversacionRepetidos repetidos = mock(ConversacionRepetidos.class);
    private final ConversacionVarios varios = mock(ConversacionVarios.class);
    private ProcesadorMensajes procesador;

    private final GuardadoAutomatico regla = regla(true);

    private static GuardadoAutomatico regla(boolean activo) {
        return new GuardadoAutomatico(new BotProperties(activo, TOPE), null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        FormatoMensajes formato = new FormatoMensajes(RELOJ);
        GastitoProperties properties = new GastitoProperties(null, null, null, new GastitoProperties.Audio(
                "http://whisper", "clave", "whisper-1", "es", Duration.ofSeconds(60), Duration.ofSeconds(60)), null,
                null, null);
        DashboardProperties dashboard = new DashboardProperties("http://localhost:8090", null);
        Tarjetas tarjetas = new Tarjetas(formato);
        ComandosIngreso comandosIngreso = new ComandosIngreso(new ParserIngreso(), catalogos,
                mock(IngresoService.class), new ResolvedorFechas(), tarjetas, RELOJ);
        procesador = new ProcesadorMensajes(interpretador, movimientos, catalogos, mock(RegistroUsoIa.class),
                new ResolvedorFechas(), tarjetas, formato, new GraficoMes(formato), comandosIngreso,
                mock(TranscripcionService.class), tickets, mock(ResumenService.class),
                new FormatoResumen(formato, new GraficoMes(formato), dashboard), mock(ConsultaService.class),
                new FormatoConsulta(formato), repetidos, mock(ConversacionJuntadas.class),
                mock(ConversacionFijos.class), mock(ConversacionAltaFijo.class), mock(PedidosNaturales.class),
                mock(ComandoDisponible.class), mock(ar.gastito.fijo.GastoFijoService.class), varios,
                mock(ConversacionCuotas.class), mock(ConversacionRecibos.class), mock(ComandosSueldo.class),
                new FormatoCostos(mock(ar.gastito.ia.CostosIaService.class), formato, RELOJ), properties, dashboard,
                new BotProperties(true, TOPE), RELOJ);
        when(catalogos.cargar()).thenReturn(new Catalogo(CATEGORIAS, MEDIOS, List.of(), Optional.empty()));
    }

    // --- La regla: se guarda solo ---

    @Test
    void unGastoClaroDeTextoSeGuardaSolo() {
        assertThat(regla.corresponde(conCategoria("3700", KIOSCO))).isTrue();
    }

    @Test
    void unGastoClaroDeAudioSeGuardaSolo() {
        assertThat(regla.corresponde(DatosDePrueba.deAudio("3700", KIOSCO, "kiosco 3700"))).isTrue();
    }

    @Test
    void sinMedioDePagoSeGuardaIgual() {
        assertThat(regla.corresponde(sinMedio("3700"))).isTrue();
    }

    @Test
    void unTicketQueCierraYDiceElMedioSeGuardaSolo() {
        assertThat(regla.corresponde(ticket("8050", SUPER, PRODUCTOS))).isTrue();
    }

    @Test
    void justoEnElTopeSeGuardaSolo() {
        assertThat(regla.corresponde(conCategoria("100000", KIOSCO))).isTrue();
    }

    // --- La regla: sigue pidiendo confirmación ---

    @Test
    void laCategoriaDudosaSeConfirma() {
        assertThat(regla.corresponde(dudoso("3700", SUPER, KIOSCO))).isFalse();
    }

    @Test
    void sinMontoSeConfirma() {
        assertThat(regla.corresponde(conCategoria(null, KIOSCO))).isFalse();
    }

    @Test
    void unaJuntadaSeConfirma() {
        Movimiento m = conCategoria("30000", KIOSCO);
        m.prepararJuntada(new BigDecimal("5000"));
        assertThat(regla.corresponde(m)).isFalse();
    }

    @Test
    void unaCompraEnCuotasSeConfirma() {
        Movimiento m = conCategoria("10000", KIOSCO);
        m.prepararCuota(3L, 1, 6);
        assertThat(regla.corresponde(m)).isFalse();
    }

    @Test
    void elPagoDeUnFijoSeConfirma() {
        Movimiento m = conCategoria("30000", KIOSCO);
        m.vincularFijo(4L, LocalDate.of(2026, 10, 1));
        assertThat(regla.corresponde(m)).isFalse();
    }

    @Test
    void unIngresoSeConfirma() {
        Movimiento sueldo = conId(Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal("50000"), null, null, null,
                AHORA, Origen.TEXTO, "cobré 50000", AHORA), ID);
        assertThat(regla.corresponde(sueldo)).isFalse();
    }

    @Test
    void unTicketQueNoCierraSeConfirma() {
        assertThat(regla.corresponde(ticket("9000", SUPER, PRODUCTOS))).isFalse();
    }

    @Test
    void unTicketQueDiceSoloTarjetaSeConfirma() {
        Movimiento m = conId(Movimiento.deTicket(new MovimientoInterpretado(new BigDecimal("8050"), "Supermercado Día",
                SUPER, List.of(), null, null, null, FECHA_TICKET), DatosTicket.de("Supermercado Día", PRODUCTOS), null,
                AHORA), ID);
        assertThat(regla.corresponde(m)).isFalse();
    }

    @Test
    void masDelTopeSeConfirma() {
        assertThat(regla.corresponde(conCategoria("100000.01", KIOSCO))).isFalse();
    }

    @Test
    void conLaConfiguracionApagadaTodoSeConfirma() {
        assertThat(regla(false).corresponde(conCategoria("3700", KIOSCO))).isFalse();
    }

    // --- El flujo ---

    private Accion.Enviar mandarGasto(String texto, Movimiento pendiente) {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(pendiente.getMonto(),
                pendiente.getDescripcion(), pendiente.getCategoria(), pendiente.getCandidatas(),
                pendiente.getMedioPago(), null, null, pendiente.getFechaHora());
        when(interpretador.interpretar(texto, PropositoIa.TEXTO))
                .thenReturn(new InterpretadorService.Interpretado(interpretado, Confianza.ALTA, null));
        when(movimientos.crearPendiente(any(), eq(Origen.TEXTO), eq(texto))).thenReturn(pendiente);
        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, texto);
        assertThat(acciones).hasSize(1).first().isInstanceOf(Accion.Enviar.class);
        procesador.tarjetaEnviada(CHAT, pendiente.getId(), TARJETA);
        return (Accion.Enviar) acciones.get(0);
    }

    private void alConfirmar(Movimiento confirmado) {
        when(movimientos.confirmar(confirmado.getId()))
                .thenReturn(new MovimientoService.Confirmacion(confirmado, confirmado.getMonto()));
    }

    private static Movimiento sinMedio(String monto) {
        return conId(Movimiento.pendiente(new MovimientoInterpretado(new BigDecimal(monto), "Pan de pancho y papas",
                KIOSCO, List.of(), null, null, null, AHORA), Origen.TEXTO, "x", AHORA), ID);
    }

    private static Movimiento guardado(Movimiento m) {
        m.confirmar();
        return m;
    }

    private List<Accion> tocar(String datos) {
        return procesador.procesarBoton(CHAT, TARJETA, datos);
    }

    private static Accion.Editar edicion(List<Accion> acciones) {
        return acciones.stream().filter(Accion.Editar.class::isInstance).map(Accion.Editar.class::cast)
                .findFirst().orElseThrow();
    }

    private static List<String> datos(Teclado teclado) {
        return teclado.botones().stream().map(Teclado.Boton::datos).toList();
    }

    @Test
    void elGastoClaroQuedaGuardadoConEditarYDeshacer() {
        alConfirmar(guardado(conCategoria("3700", KIOSCO)));

        Accion.Enviar tarjeta = mandarGasto("3700 pan de pancho y papas efectivo", conCategoria("3700", KIOSCO));

        assertThat(tarjeta.html()).isEqualTo("""
                ✅ <b>Guardado</b> · <b>$3.700</b> · 🍬 Kiosco · 💵 Efectivo
                Pan de pancho y papas""");
        assertThat(datos(tarjeta.teclado())).containsExactly("e:15", "du:15");
        assertThat(tarjeta.tarjetaDeMovimiento()).isEqualTo(ID);
        verify(movimientos).confirmar(ID);
    }

    @Test
    void sinMedioDePagoSumaLaFilaDeBotonesRapidos() {
        alConfirmar(guardado(sinMedio("3700")));

        Accion.Enviar tarjeta = mandarGasto("3700 pan de pancho", sinMedio("3700"));

        assertThat(tarjeta.html()).contains("💳 <i>Sin especificar</i>");
        assertThat(tarjeta.teclado().filas().get(0)).extracting(Teclado.Boton::texto)
                .containsExactly("💵 Efectivo", "💳 Débito");
        assertThat(datos(tarjeta.teclado())).containsExactly("gm:15:1", "gm:15:2", "e:15", "du:15");
    }

    @Test
    void elMedioRapidoActualizaElGuardadoYSacaLaFila() {
        when(movimientos.buscar(ID)).thenReturn(guardado(sinMedio("3700")));
        Movimiento conDebito = guardado(conId(Movimiento.pendiente(new MovimientoInterpretado(new BigDecimal("3700"),
                "Pan de pancho y papas", KIOSCO, List.of(), DEBITO, null, null, AHORA), Origen.TEXTO, "x", AHORA), ID));
        when(movimientos.editar(ID, new BigDecimal("3700"), "Pan de pancho y papas", AHORA, KIOSCO.getId(),
                DEBITO.getId(), null)).thenReturn(conDebito);

        List<Accion> acciones = tocar("gm:15:2");

        assertThat(edicion(acciones).html()).contains("💳 Débito");
        assertThat(datos(edicion(acciones).teclado())).containsExactly("e:15", "du:15");
        assertThat(acciones).contains(new Accion.Avisar("💳 Débito"));
    }

    @Test
    void deshacerBorraElGasto() {
        when(movimientos.borrar(ID)).thenReturn(guardado(conCategoria("3700", KIOSCO)));

        List<Accion> acciones = tocar("du:15");

        assertThat(edicion(acciones).html()).startsWith("↩️ <b>Deshecho</b> · <s>$3.700</s> · 🍬 Kiosco");
        assertThat(edicion(acciones).teclado().isVacio()).isTrue();
        verify(movimientos).borrar(ID);
    }

    @Test
    void deshacerUnTicketBorraTambienLaFoto() {
        Movimiento leido = ticket("8050", SUPER, PRODUCTOS);
        leido.asociarFotoTicket("tickets/2026/10/15.jpg");
        when(movimientos.borrar(ID)).thenReturn(leido);

        tocar("du:15");

        verify(tickets).borrarArchivoDeFoto("tickets/2026/10/15.jpg");
    }

    @Test
    void editarSobreUnGuardadoAbreLosMismosCampos() {
        when(movimientos.buscar(ID)).thenReturn(guardado(conCategoria("3700", KIOSCO)));

        Accion.Editar editada = edicion(tocar("e:15"));

        assertThat(editada.html()).startsWith("✅ <b>Guardado</b> · <b>$3.700</b>");
        assertThat(datos(editada.teclado())).containsExactly("o:15", "em:15", "mp:15", "ef:15", "ed:15", "v:15");
    }

    @Test
    void elMontoEscritoSobreUnGuardadoLoCorrigeYVuelveAlGuardado() {
        Movimiento antes = guardado(conCategoria("3700", KIOSCO));
        when(movimientos.buscar(ID)).thenReturn(antes);
        tocar("em:15");
        when(movimientos.cambiarMonto(ID, new BigDecimal("3500"))).thenThrow(new MovimientoNoPendienteException(antes));
        when(movimientos.editar(ID, new BigDecimal("3500"), "Pan de pancho y papas", AHORA, KIOSCO.getId(),
                EFECTIVO.getId(), null)).thenReturn(guardado(conCategoria("3500", KIOSCO)));

        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "3500");

        assertThat(acciones).contains(new Accion.Borrar(MENSAJE_USUARIO));
        assertThat(edicion(acciones).html()).startsWith("✅ <b>Guardado</b> · <b>$3.500</b>");
        assertThat(datos(edicion(acciones).teclado())).containsExactly("e:15", "du:15");
    }

    @Test
    void laCategoriaElegidaSobreUnGuardadoLoCorrige() {
        Movimiento antes = guardado(conCategoria("3700", KIOSCO));
        when(movimientos.elegirCategoria(ID, SUPER.getId())).thenThrow(new MovimientoNoPendienteException(antes));
        when(movimientos.editar(ID, new BigDecimal("3700"), "Pan de pancho y papas", AHORA, SUPER.getId(),
                EFECTIVO.getId(), null)).thenReturn(guardado(conCategoria("3700", SUPER)));

        Accion.Editar editada = edicion(tocar("k:15:2"));

        assertThat(editada.html()).contains("🛒 Supermercado");
    }

    @Test
    void unGastoDudosoSigueConLaTarjetaDeConfirmacion() {
        Accion.Enviar tarjeta = mandarGasto("3700 pan de pancho", dudoso("3700", SUPER, KIOSCO));

        assertThat(tarjeta.html()).startsWith("🧾 <b>Nuevo gasto</b>");
        verify(movimientos, never()).confirmar(any());
    }

    @Test
    void unaCompraRepetidaPreguntaAntesDeGuardar() {
        Movimiento nuevo = conCategoria("3700", KIOSCO);
        Accion.Enviar pregunta = Accion.Enviar.texto("🔁 ¿Ya lo tenías registrado?");
        when(repetidos.siEsRepetido(nuevo)).thenReturn(Optional.of(pregunta));

        assertThat(mandarGasto("kiosco 3700", nuevo)).isSameAs(pregunta);
        verify(movimientos, never()).confirmar(any());
    }

    @Test
    void unTicketClaroSeGuardaSolo() {
        Movimiento leido = ticket("8050", SUPER, PRODUCTOS);
        when(tickets.leer(any(), eq(null))).thenReturn(new TicketService.Leido(leido));
        alConfirmar(guardado(ticket("8050", SUPER, PRODUCTOS)));

        List<Accion> acciones = procesador.procesarFoto(CHAT, MENSAJE_USUARIO,
                new FotoRecibida(5L, null, () -> new byte[] {1}));

        assertThat(((Accion.Enviar) acciones.get(0)).html())
                .isEqualTo("✅ <b>Guardado</b> · <b>$8.050</b> · 🛒 Supermercado · 💳 Débito\nSupermercado Día · 3 productos");
    }

    @Test
    void variosGastosClarosSeGuardanCadaUnoConSuTarjeta() {
        Movimiento kiosco = conId(conCategoria("1500", KIOSCO), 15);
        Movimiento sup = conId(conCategoria("2000", SUPER), 16);
        interpretar("kiosco 1500", kiosco);
        interpretar("super 2000", sup);
        alConfirmar(guardado(conId(conCategoria("1500", KIOSCO), 15)));
        alConfirmar(guardado(conId(conCategoria("2000", SUPER), 16)));

        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "kiosco 1500 y super 2000");

        assertThat(acciones).hasSize(2).allSatisfy(a -> assertThat(((Accion.Enviar) a).html())
                .startsWith("✅ <b>Guardado</b>"));
        verify(varios, never()).tarjeta(any(), any());
    }

    @Test
    void variosGastosConUnoDudosoVanALaTarjetaCombinada() {
        Movimiento kiosco = conId(conCategoria("1500", KIOSCO), 15);
        Movimiento duda = conId(dudoso("2000", SUPER, KIOSCO), 16);
        interpretar("kiosco 1500", kiosco);
        interpretar("algo 2000", duda);
        Accion.Enviar combinada = Accion.Enviar.texto("2 gastos");
        when(varios.tarjeta(List.of(kiosco, duda), "")).thenReturn(combinada);

        assertThat(procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "kiosco 1500 y algo 2000"))
                .containsExactly(combinada);
        verify(movimientos, never()).confirmar(any());
    }

    private void interpretar(String texto, Movimiento m) {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(m.getMonto(), m.getDescripcion(),
                m.getCategoria(), m.getCandidatas(), m.getMedioPago(), null, null, m.getFechaHora());
        when(interpretador.interpretar(texto, PropositoIa.TEXTO))
                .thenReturn(new InterpretadorService.Interpretado(interpretado, Confianza.ALTA, null));
        when(movimientos.crearPendiente(any(), eq(Origen.TEXTO), eq(texto))).thenReturn(m);
    }

    @Test
    void conLaConfiguracionApagadaVuelveElBotonGuardar() {
        // El comportamiento de siempre está cubierto en ProcesadorMensajesTest (que corre con la configuración en
        // false); acá, que la regla apagada no deja guardar nada solo.
        assertThat(regla(false).corresponde(ticket("8050", SUPER, PRODUCTOS))).isFalse();
    }
}
