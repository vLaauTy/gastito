package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.CATEGORIAS;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.KIOSCO;
import static ar.gastito.bot.DatosDePrueba.MEDIOS;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SALIDAS;
import static ar.gastito.bot.DatosDePrueba.SUPER;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static ar.gastito.bot.DatosDePrueba.conId;
import static ar.gastito.bot.DatosDePrueba.confirmado;
import static ar.gastito.bot.DatosDePrueba.deAudio;
import static ar.gastito.bot.DatosDePrueba.dudoso;
import static ar.gastito.bot.DatosDePrueba.PRODUCTOS;
import static ar.gastito.bot.DatosDePrueba.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import ar.gastito.audio.TranscripcionNoDisponibleException;
import ar.gastito.audio.TranscripcionService;
import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.ia.ResumenUsoIa;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.ParserIngreso;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoIncompletoException;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TotalPorCategoria;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.TicketService;
import ar.gastito.ticket.ValidadorTicket;

class ProcesadorMensajesTest {

    static final long CHAT = 42;
    static final int TARJETA = 100;
    static final int MENSAJE_USUARIO = 101;

    private final InterpretadorService interpretador = mock(InterpretadorService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final RegistroUsoIa usoIa = mock(RegistroUsoIa.class);
    private final TranscripcionService transcripciones = mock(TranscripcionService.class);
    private final IngresoService ingresos = mock(IngresoService.class);
    private final TicketService tickets = mock(TicketService.class);
    private ProcesadorMensajes procesador;

    @BeforeEach
    void setUp() {
        FormatoMensajes formato = new FormatoMensajes(RELOJ);
        GastitoProperties properties = new GastitoProperties(null, null, null, new GastitoProperties.Audio(
                "http://whisper", "clave", "whisper-1", "es", Duration.ofSeconds(60), Duration.ofSeconds(60)), null);
        Tarjetas tarjetas = new Tarjetas(formato);
        ComandosIngreso comandosIngreso = new ComandosIngreso(new ParserIngreso(), catalogos, ingresos,
                new ResolvedorFechas(), tarjetas, RELOJ);
        procesador = new ProcesadorMensajes(interpretador, movimientos, catalogos, usoIa, new ResolvedorFechas(),
                tarjetas, formato, new GraficoMes(formato), comandosIngreso, transcripciones, tickets, properties,
                new DashboardProperties("http://localhost:8090", null), RELOJ);
        when(catalogos.cargar()).thenReturn(new Catalogo(CATEGORIAS, MEDIOS, List.of(), Optional.empty()));
        when(tickets.tamanioMaximo()).thenReturn(DataSize.ofMegabytes(10));
    }

    /** El usuario manda un gasto y el interpretador devuelve lo que se guarda como pendiente. */
    private Accion.Enviar mandarGasto(String texto, Movimiento pendiente, Confianza confianza, Long usoIaId) {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(pendiente.getMonto(),
                pendiente.getDescripcion(), pendiente.getCategoria(), pendiente.getCandidatas(),
                pendiente.getMedioPago(), null, null, pendiente.getFechaHora());
        when(interpretador.interpretar(texto, PropositoIa.TEXTO))
                .thenReturn(new InterpretadorService.Interpretado(interpretado, confianza, usoIaId));
        when(movimientos.crearPendiente(any(), eq(Origen.TEXTO), eq(texto))).thenReturn(pendiente);

        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, texto);

        assertThat(acciones).hasSize(1).first().isInstanceOf(Accion.Enviar.class);
        Accion.Enviar tarjeta = (Accion.Enviar) acciones.get(0);
        procesador.tarjetaEnviada(CHAT, pendiente.getId(), TARJETA);
        return tarjeta;
    }

    private List<Accion> tocar(String datos) {
        return procesador.procesarBoton(CHAT, TARJETA, datos);
    }

    private static Accion.Editar edicion(List<Accion> acciones) {
        return acciones.stream().filter(Accion.Editar.class::isInstance).map(Accion.Editar.class::cast)
                .findFirst().orElseThrow();
    }

    // --- Gasto con categoría clara ---

    @Test
    void gastoClaroMuestraLaTarjetaConGuardar() {
        Accion.Enviar tarjeta = mandarGasto("kiosco 1500", conCategoria("1500", KIOSCO), Confianza.ALTA, null);

        assertThat(tarjeta.html()).startsWith("🧾 <b>Nuevo gasto</b>\n💰 <b>$1.500</b>").contains("🍬 Kiosco");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("g:15", "e:15", "c:15");
        assertThat(tarjeta.tarjetaDeMovimiento()).isEqualTo(ID);
        verify(usoIa, never()).asociarMovimiento(any(), any());
    }

    @Test
    void guardarTransformaLaTarjetaConElTotalDelMes() {
        when(movimientos.confirmar(ID))
                .thenReturn(new MovimientoService.Confirmacion(confirmado("1500", KIOSCO), new BigDecimal("5200")));

        List<Accion> acciones = tocar("g:15");

        Accion.Editar editada = edicion(acciones);
        assertThat(editada.mensajeId()).isEqualTo(TARJETA);
        assertThat(editada.html()).startsWith("✅ <b>Guardado</b>").endsWith("llevás <b>$5.200</b> en 🍬 Kiosco.");
        assertThat(editada.teclado().isVacio()).isTrue();
        assertThat(acciones).contains(new Accion.Avisar("✅ Guardado"));
    }

    @Test
    void cancelarTransformaLaTarjeta() {
        when(movimientos.cancelar(ID)).thenReturn(conCategoria("1500", KIOSCO));

        Accion.Editar editada = edicion(tocar("c:15"));

        assertThat(editada.html()).startsWith("❌ <b>Cancelado</b>");
        assertThat(editada.teclado().isVacio()).isTrue();
    }

    // --- Gasto dudoso: elegir la categoría con botones ---

    @Test
    void gastoDudosoPreguntaLaCategoriaSinGuardar() {
        Accion.Enviar tarjeta = mandarGasto("3700 en pan de pancho y papas, efectivo", dudoso("3700", SUPER, KIOSCO),
                Confianza.MEDIA, 7L);

        assertThat(tarjeta.html()).contains("🏷 <b>¿Qué categoría?</b>");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🛒 Supermercado", "🍬 Kiosco", "Otra…", "✏️ Editar", "❌ Cancelar");
        verify(usoIa).asociarMovimiento(7L, ID);
    }

    @Test
    void elegirUnaCandidataActualizaLaTarjetaEnElLugarYApareceGuardar() {
        when(movimientos.elegirCategoria(ID, SUPER.getId())).thenReturn(conCategoria("3700", SUPER));

        List<Accion> acciones = tocar("k:15:2");

        assertThat(acciones).hasSize(1);
        Accion.Editar editada = edicion(acciones);
        assertThat(editada.mensajeId()).isEqualTo(TARJETA);
        assertThat(editada.html()).contains("🛒 Supermercado").doesNotContain("¿Qué categoría?");
        assertThat(editada.teclado().botones()).extracting(Teclado.Boton::texto).contains("✅ Guardar");
    }

    @Test
    void otraAbreTodasLasCategorias() {
        when(movimientos.buscar(ID)).thenReturn(dudoso("3700", SUPER, KIOSCO));

        Accion.Editar editada = edicion(tocar("o:15"));

        assertThat(editada.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🍬 Kiosco", "🛒 Supermercado", "🍻 Salidas", "↩ Volver");
    }

    @Test
    void elegirDesdeOtra() {
        when(movimientos.elegirCategoria(ID, SALIDAS.getId())).thenReturn(conCategoria("3700", SALIDAS));

        assertThat(edicion(tocar("k:15:3")).html()).contains("🍻 Salidas");
    }

    @Test
    void guardarSinCategoriaAvisa() {
        when(movimientos.confirmar(ID)).thenThrow(new MovimientoIncompletoException(ID));
        when(movimientos.buscar(ID)).thenReturn(dudoso("3700", SUPER, KIOSCO));

        List<Accion> acciones = tocar("g:15");

        assertThat(acciones)
                .contains(new Accion.Avisar("Antes de guardar falta elegir la categoría o poner el monto"));
        assertThat(edicion(acciones).teclado().botones()).extracting(Teclado.Boton::texto)
                .doesNotContain("✅ Guardar");
    }

    // --- Editar por campo ---

    @Test
    void editarMuestraLosCampos() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));

        assertThat(edicion(tocar("e:15")).teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🏷 Categoría", "💰 Monto", "💳 Medio de pago", "📅 Fecha", "📝 Descripción",
                        "↩ Volver");
    }

    @Test
    void editarElMontoBorraElMensajeDelUsuarioYActualizaLaTarjeta() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        Accion.Editar pidiendo = edicion(tocar("em:15"));
        assertThat(pidiendo.html()).contains("Escribí el nuevo monto");
        assertThat(pidiendo.teclado().botones()).extracting(Teclado.Boton::texto).containsExactly("↩ Volver");

        when(movimientos.cambiarMonto(ID, new BigDecimal("4000"))).thenReturn(conCategoria("4000", KIOSCO));
        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "4.000");

        assertThat(acciones).hasSize(2);
        assertThat(acciones.get(0)).isEqualTo(new Accion.Borrar(MENSAJE_USUARIO));
        Accion.Editar tarjeta = edicion(acciones);
        assertThat(tarjeta.mensajeId()).isEqualTo(TARJETA);
        assertThat(tarjeta.html()).contains("💰 <b>$4.000</b>");
        verifyNoInteractions(interpretador);
    }

    @Test
    void editarLaFecha() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("ef:15");
        LocalDateTime ayer = LocalDateTime.of(2026, 10, 12, 21, 30);
        when(movimientos.cambiarFechaHora(ID, ayer)).thenReturn(conCategoria("3700", KIOSCO));

        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "ayer 21:30");

        verify(movimientos).cambiarFechaHora(ID, ayer);
        assertThat(acciones.get(0)).isEqualTo(new Accion.Borrar(MENSAJE_USUARIO));
    }

    @Test
    void editarLaDescripcion() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("ed:15");
        when(movimientos.cambiarDescripcion(ID, "Alfajores")).thenReturn(conCategoria("3700", KIOSCO));

        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "alfajores");

        verify(movimientos).cambiarDescripcion(ID, "Alfajores");
    }

    @Test
    void editarElMedioDePagoConBotones() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        assertThat(edicion(tocar("mp:15")).teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("Efectivo", "Débito", "Sin medio de pago", "↩ Volver");

        when(movimientos.cambiarMedioPago(ID, null)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("p:15:0");
        verify(movimientos).cambiarMedioPago(ID, null);

        when(movimientos.cambiarMedioPago(ID, 2L)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("p:15:2");
        verify(movimientos).cambiarMedioPago(ID, 2L);
    }

    @Test
    void volverDejaLaTarjetaComoEstabaYNoEsperaMas() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");

        Accion.Editar editada = edicion(tocar("v:15"));
        assertThat(editada.teclado().botones()).extracting(Teclado.Boton::texto).contains("✅ Guardar");

        // Un número ya no se toma como monto de esa tarjeta.
        when(interpretador.interpretar("500", PropositoIa.TEXTO)).thenReturn(new InterpretadorService.NoEsGasto());
        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "500");
        verify(movimientos, never()).cambiarMonto(anyLong(), any());
    }

    @Test
    void siEnVezDelValorMandaOtroGastoLaTarjetaVuelveYSeProcesaElNuevo() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");
        Movimiento otro = DatosDePrueba.conId(conCategoria("2000", SUPER), 16L);
        when(interpretador.interpretar("super 2000", PropositoIa.TEXTO)).thenReturn(new InterpretadorService.Interpretado(
                MovimientoInterpretado.conCategoria(new BigDecimal("2000"), "Super", SUPER, null, null, null, AHORA),
                Confianza.ALTA, null));
        when(movimientos.crearPendiente(any(), any(), anyString())).thenReturn(otro);

        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "super 2000");

        assertThat(acciones).hasSize(2);
        assertThat(edicion(acciones).mensajeId()).isEqualTo(TARJETA);
        assertThat(acciones.get(1)).isInstanceOf(Accion.Enviar.class);
        verify(movimientos, never()).cambiarMonto(anyLong(), any());
    }

    // --- Gasto sin monto ---

    @Test
    void sinMontoLoPideEnLaTarjetaYSeCompletaEscribiendolo() {
        Accion.Enviar tarjeta = mandarGasto("pan y papas en efectivo", conCategoria(null, KIOSCO), Confianza.ALTA,
                8L);
        assertThat(tarjeta.html()).contains("Falta el monto").endsWith("Escribime el monto.");

        when(movimientos.cambiarMonto(ID, new BigDecimal("3700"))).thenReturn(conCategoria("3700", KIOSCO));
        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "3700");

        assertThat(acciones.get(0)).isEqualTo(new Accion.Borrar(MENSAJE_USUARIO));
        assertThat(edicion(acciones).mensajeId()).isEqualTo(TARJETA);
        assertThat(edicion(acciones).teclado().botones()).extracting(Teclado.Boton::texto).contains("✅ Guardar");
    }

    @Test
    void sinMontoNiCategoriaElegirLaCategoriaSigueEsperandoElMonto() {
        mandarGasto("pan y papas", dudoso(null, SUPER, KIOSCO), Confianza.MEDIA, 8L);
        when(movimientos.elegirCategoria(ID, SUPER.getId())).thenReturn(conCategoria(null, SUPER));

        Accion.Editar editada = edicion(tocar("k:15:2"));
        assertThat(editada.html()).endsWith("Escribime el monto.");

        when(movimientos.cambiarMonto(ID, new BigDecimal("3700"))).thenReturn(conCategoria("3700", SUPER));
        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "3700");
        verify(movimientos).cambiarMonto(ID, new BigDecimal("3700"));
    }

    // --- Casos borde ---

    @Test
    void tocarUnaTarjetaYaGuardadaNoHaceNadaRaro() {
        when(movimientos.cancelar(ID)).thenThrow(new MovimientoNoPendienteException(confirmado("3700", KIOSCO)));

        List<Accion> acciones = tocar("c:15");

        assertThat(edicion(acciones).html()).startsWith("✅ <b>Guardado</b>");
        assertThat(edicion(acciones).teclado().isVacio()).isTrue();
        assertThat(acciones).contains(new Accion.Avisar("Ese gasto ya estaba guardado"));
    }

    @Test
    void botonesInvalidos() {
        when(movimientos.confirmar(99L)).thenThrow(new NoSuchElementException());

        assertThat(tocar("g:99")).containsExactly(new Accion.Avisar("No encontré ese gasto"));
        assertThat(tocar("x:15")).containsExactly(new Accion.Avisar("Botón inválido"));
        assertThat(tocar("g:abc")).containsExactly(new Accion.Avisar("Botón inválido"));
        assertThat(tocar("k:15:abc")).containsExactly(new Accion.Avisar("Botón inválido"));
        assertThat(procesador.procesarBoton(CHAT, TARJETA, null))
                .containsExactly(new Accion.Avisar("Botón inválido"));
    }

    @Test
    void siLaIaNoEstaDisponibleSugiereElFormatoSimple() {
        when(interpretador.interpretar(anyString(), eq(PropositoIa.TEXTO)))
                .thenThrow(new IaNoDisponibleException("No pude consultar a la IA", null));

        Accion.Enviar r = (Accion.Enviar) procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "algo largo").get(0);

        assertThat(r.html()).contains("No pude consultar a la IA").contains("kiosco 1500");
        verify(movimientos, never()).crearPendiente(any(), any(), anyString());
    }

    @Test
    void mensajeQueNoEsGasto() {
        when(interpretador.interpretar("hola", PropositoIa.TEXTO)).thenReturn(new InterpretadorService.NoEsGasto());

        assertThat(texto("hola")).contains("No encontré un gasto");
    }

    // --- Audios ---

    static final byte[] AUDIO = { 1, 2, 3 };

    private static AudioRecibido audio(int segundos) {
        return new AudioRecibido(segundos, "voz.oga", () -> AUDIO);
    }

    private List<Accion> mandarAudio(String escuchado) {
        when(transcripciones.transcribir(AUDIO, "voz.oga")).thenReturn(escuchado);
        return procesador.procesarAudio(CHAT, MENSAJE_USUARIO, audio(5));
    }

    @Test
    void unAudioTerminaEnLaMismaTarjetaQueUnTextoConLoQueEscucho() {
        Movimiento pendiente = deAudio("3700", SUPER, "Gasto 3700 en pan de pancho y papas, efectivo");
        when(interpretador.interpretar("Gasto 3700 en pan de pancho y papas, efectivo", PropositoIa.AUDIO))
                .thenReturn(new InterpretadorService.Interpretado(new MovimientoInterpretado(new BigDecimal("3700"),
                        "Pan de pancho y papas", SUPER, List.of(), null, null, null, AHORA), Confianza.ALTA, 9L));
        when(movimientos.crearPendiente(any(), eq(Origen.AUDIO),
                eq("Gasto 3700 en pan de pancho y papas, efectivo"))).thenReturn(pendiente);

        List<Accion> acciones = mandarAudio("Gasto 3700 en pan de pancho y papas, efectivo.");

        assertThat(acciones).hasSize(1);
        Accion.Enviar tarjeta = (Accion.Enviar) acciones.get(0);
        assertThat(tarjeta.html())
                .startsWith("🎙 Escuché: \"Gasto 3700 en pan de pancho y papas, efectivo\"\n\n🧾 <b>Nuevo gasto</b>");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("g:15", "e:15", "c:15");
        assertThat(tarjeta.tarjetaDeMovimiento()).isEqualTo(ID);
        verify(usoIa).asociarMovimiento(9L, ID);
    }

    @Test
    void unAudioDudosoPreguntaLaCategoriaComoUnTexto() {
        Movimiento pendiente = conId(Movimiento.pendiente(new MovimientoInterpretado(new BigDecimal("3700"),
                "Pan y papas", null, List.of(SUPER, KIOSCO), null, null, null, AHORA), Origen.AUDIO, "3700 pan y papas",
                AHORA), ID);
        when(interpretador.interpretar("3700 pan y papas", PropositoIa.AUDIO))
                .thenReturn(new InterpretadorService.Interpretado(new MovimientoInterpretado(new BigDecimal("3700"),
                        "Pan y papas", null, List.of(SUPER, KIOSCO), null, null, null, AHORA), Confianza.MEDIA, 9L));
        when(movimientos.crearPendiente(any(), eq(Origen.AUDIO), eq("3700 pan y papas"))).thenReturn(pendiente);

        Accion.Enviar tarjeta = (Accion.Enviar) mandarAudio("3700 pan y papas").get(0);

        assertThat(tarjeta.html()).contains("🎙 Escuché", "¿Qué categoría?");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::texto)
                .startsWith("🛒 Supermercado", "🍬 Kiosco", "Otra…");
    }

    @Test
    void audioDemasiadoLargoNoSeDescargaNiSeTranscribe() {
        AudioRecibido largo = new AudioRecibido(75, "voz.oga", () -> {
            throw new AssertionError("no tenía que descargarse");
        });

        Accion.Enviar r = (Accion.Enviar) procesador.procesarAudio(CHAT, MENSAJE_USUARIO, largo).get(0);

        assertThat(r.html()).contains("75 segundos", "máximo es 60", "uno más corto");
        verifyNoInteractions(transcripciones, interpretador);
    }

    @Test
    void whisperApagadoPideMandarloEscrito() {
        when(transcripciones.transcribir(AUDIO, "voz.oga")).thenThrow(new TranscripcionNoDisponibleException(
                TranscripcionNoDisponibleException.Motivo.SIN_RESPUESTA, "El servidor de Whisper no respondió"));

        Accion.Enviar r = (Accion.Enviar) procesador.procesarAudio(CHAT, MENSAJE_USUARIO, audio(5)).get(0);

        assertThat(r.html()).isEqualTo(
                "No pude transcribir el audio, ¿la VM está prendida? Mientras tanto mandámelo escrito.");
        verifyNoInteractions(interpretador);
    }

    @Test
    void claveDeWhisperRechazada() {
        when(transcripciones.transcribir(AUDIO, "voz.oga")).thenThrow(new TranscripcionNoDisponibleException(
                TranscripcionNoDisponibleException.Motivo.CLAVE_RECHAZADA, "rechazada"));

        assertThat(((Accion.Enviar) procesador.procesarAudio(CHAT, MENSAJE_USUARIO, audio(5)).get(0)).html())
                .contains("WHISPER_API_KEY");
    }

    @Test
    void falloAlDescargarDeTelegram() {
        AudioRecibido roto = new AudioRecibido(5, "voz.oga", () -> {
            throw new java.io.IOException("sin red");
        });

        assertThat(((Accion.Enviar) procesador.procesarAudio(CHAT, MENSAJE_USUARIO, roto).get(0)).html())
                .contains("No pude descargar el audio");
        verifyNoInteractions(transcripciones);
    }

    @Test
    void audioSinNadaQueEscuchar() {
        assertThat(((Accion.Enviar) mandarAudio("  .  ").get(0)).html()).contains("No escuché nada");
        verifyNoInteractions(interpretador);
    }

    @Test
    void audioSinUnGastoMuestraLoQueEscucho() {
        when(interpretador.interpretar("Hola, ¿cómo estás", PropositoIa.AUDIO))
                .thenReturn(new InterpretadorService.NoEsGasto());

        assertThat(((Accion.Enviar) mandarAudio("Hola, ¿cómo estás?").get(0)).html())
                .startsWith("🎙 Escuché: \"Hola, ¿cómo estás\"")
                .contains("No encontré un gasto en lo que escuché");
        verify(movimientos, never()).crearPendiente(any(), any(), anyString());
    }

    @Test
    void limpiaElPuntoFinalDeWhisper() {
        assertThat(ProcesadorMensajes.limpiarTranscripcion(" Kiosco 1500. ")).isEqualTo("Kiosco 1500");
        assertThat(ProcesadorMensajes.limpiarTranscripcion("¿Cuánto?")).isEqualTo("¿Cuánto");
        assertThat(ProcesadorMensajes.limpiarTranscripcion("3.700")).isEqualTo("3.700");
    }

    // --- Audio mientras se edita un campo ---

    @Test
    void audioEditandoElMontoMuestraLoQueEscuchoSinAplicarlo() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");

        List<Accion> acciones = mandarAudio("4000.");

        assertThat(acciones).hasSize(2);
        assertThat(acciones.get(0)).isEqualTo(new Accion.Borrar(MENSAJE_USUARIO));
        Accion.Editar propuesta = edicion(acciones);
        assertThat(propuesta.mensajeId()).isEqualTo(TARJETA);
        assertThat(propuesta.html()).endsWith("🎙 Escuché: \"4000\" → <b>Monto: $4.000</b>");
        assertThat(propuesta.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("✅ Aplicar", "↩ Cancelar edición");
        verify(movimientos, never()).cambiarMonto(anyLong(), any());
        verifyNoInteractions(interpretador);
    }

    @Test
    void aplicarLaPropuestaCambiaElMonto() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");
        mandarAudio("4.000 pesos");
        when(movimientos.cambiarMonto(ID, new BigDecimal("4000"))).thenReturn(conCategoria("4000", KIOSCO));

        List<Accion> acciones = tocar("a:15");

        verify(movimientos).cambiarMonto(ID, new BigDecimal("4000"));
        assertThat(edicion(acciones).html()).contains("💰 <b>$4.000</b>").doesNotContain("Escuché");
        assertThat(edicion(acciones).teclado().botones()).extracting(Teclado.Boton::texto).contains("✅ Guardar");
        assertThat(acciones).contains(new Accion.Avisar("✅ Aplicado"));
        // Ya no se espera nada: el próximo audio es un gasto nuevo.
        assertThat(tocar("a:15")).contains(new Accion.Avisar("Ese cambio ya no está pendiente"));
    }

    @Test
    void cancelarLaEdicionNoCambiaNada() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");
        mandarAudio("4000");

        Accion.Editar editada = edicion(tocar("v:15"));

        assertThat(editada.html()).contains("💰 <b>$3.700</b>").doesNotContain("Escuché");
        verify(movimientos, never()).cambiarMonto(anyLong(), any());
    }

    @Test
    void audioEditandoLaFechaProponeLaFecha() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("ef:15");

        Accion.Editar propuesta = edicion(mandarAudio("Ayer 21:30."));

        assertThat(propuesta.html()).endsWith("→ <b>Fecha: Ayer 21:30</b>");
        when(movimientos.cambiarFechaHora(ID, LocalDateTime.of(2026, 10, 12, 21, 30)))
                .thenReturn(conCategoria("3700", KIOSCO));
        tocar("a:15");
        verify(movimientos).cambiarFechaHora(ID, LocalDateTime.of(2026, 10, 12, 21, 30));
    }

    @Test
    void audioQueNoSirveParaElCampoEsUnGastoNuevo() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");
        when(interpretador.interpretar("Kiosco alfajor", PropositoIa.AUDIO))
                .thenReturn(new InterpretadorService.NoEsGasto());

        List<Accion> acciones = mandarAudio("Kiosco alfajor");

        // La tarjeta que esperaba el monto vuelve a su estado y el audio se interpreta como gasto.
        assertThat(edicion(acciones).html()).doesNotContain("Escribí el nuevo monto");
        verify(interpretador).interpretar("Kiosco alfajor", PropositoIa.AUDIO);
    }

    @Test
    void textoConUnaPropuestaPendienteSeAplicaDirecto() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");
        mandarAudio("4000");
        when(movimientos.cambiarMonto(ID, new BigDecimal("5000"))).thenReturn(conCategoria("5000", KIOSCO));

        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "5000");

        verify(movimientos).cambiarMonto(ID, new BigDecimal("5000"));
        verify(movimientos, never()).cambiarMonto(ID, new BigDecimal("4000"));
    }

    // --- Fotos de tickets ---

    static final byte[] FOTO = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

    private static FotoRecibida foto(String texto) {
        return new FotoRecibida(5L, texto, () -> FOTO);
    }

    private Accion.Enviar mandarFoto(String texto) {
        List<Accion> acciones = procesador.procesarFoto(CHAT, MENSAJE_USUARIO, foto(texto));
        assertThat(acciones).hasSize(1).first().isInstanceOf(Accion.Enviar.class);
        return (Accion.Enviar) acciones.get(0);
    }

    @Test
    void unaFotoTerminaEnLaMismaTarjetaDeConfirmacionQueUnTexto() {
        Movimiento leido = ticket("8050", SUPER, PRODUCTOS);
        when(tickets.leer(FOTO, "con débito")).thenReturn(new TicketService.Leido(leido));

        Accion.Enviar tarjeta = mandarFoto("con débito");

        assertThat(tarjeta.html())
                .startsWith("🧾 <b>Ticket</b> · Supermercado Día\n📅 sáb 10/10 17:20\n🛒 <b>Productos</b>\n<pre>")
                .contains("Leche 1 L ×2 ")
                .contains("💰 Total <b>$8.050</b>\n💳 Débito\n🛒 Supermercado")
                .doesNotContain("⚠️");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("g:15", "e:15", "c:15");
        assertThat(tarjeta.tarjetaDeMovimiento()).isEqualTo(ID);

        // Guardar funciona igual que con un texto.
        Movimiento guardado = ticket("8050", SUPER, PRODUCTOS);
        guardado.confirmar();
        when(movimientos.confirmar(ID))
                .thenReturn(new MovimientoService.Confirmacion(guardado, new BigDecimal("20000")));
        procesador.tarjetaEnviada(CHAT, ID, TARJETA);
        Accion.Editar editada = edicion(tocar("g:15"));
        assertThat(editada.html()).startsWith("✅ <b>Guardado</b> · Supermercado Día")
                .endsWith("llevás <b>$20.000</b> en 🛒 Supermercado.");
    }

    @Test
    void unTicketSinCategoriaClaraLaPreguntaConBotones() {
        when(tickets.leer(FOTO, null))
                .thenReturn(new TicketService.Leido(ticket("8050", null, PRODUCTOS, SUPER, KIOSCO)));

        Accion.Enviar tarjeta = mandarFoto(null);

        assertThat(tarjeta.html()).contains("🏷 <b>¿Qué categoría?</b>");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("k:15:2", "k:15:1", "o:15", "e:15", "c:15");
    }

    @Test
    void siLosProductosNoSumanElTotalLaTarjetaAvisa() {
        when(tickets.leer(FOTO, null)).thenReturn(new TicketService.Leido(ticket("9000", SUPER, PRODUCTOS)));

        assertThat(mandarFoto(null).html()).endsWith(
                "⚠️ Los productos suman <b>$8.050</b> y el total dice <b>$9.000</b>. Revisalo antes de guardar.");
    }

    @Test
    void unaFotoQueNoEsTicketLoDice() {
        when(tickets.leer(FOTO, null)).thenReturn(new TicketService.NoEsTicket());

        assertThat(mandarFoto(null).html()).startsWith("Esa foto no parece un ticket 🤔");
        verify(movimientos, never()).crearPendiente(any(), any(), any());
    }

    @Test
    void unaFotoBorrosaPideOtraConBuenaLuz() {
        when(tickets.leer(FOTO, null)).thenReturn(new TicketService.Ilegible(ValidadorTicket.Motivo.BORROSA));

        assertThat(mandarFoto(null).html()).contains("la foto salió borrosa").contains("sin reflejos");
    }

    @Test
    void unTicketSinTotalNoGuardaYPideOtraFoto() {
        when(tickets.leer(FOTO, null)).thenReturn(new TicketService.Ilegible(ValidadorTicket.Motivo.SIN_TOTAL));

        assertThat(mandarFoto(null).html()).startsWith("No encontré el total del ticket 🤔 No guardé nada.")
                .contains("más clara").contains("entero").contains("sin reflejos");
    }

    @Test
    void unaImagenDeMasDeDiezMegasNoSeDescarga() {
        when(tickets.superaElTamanio(20_000_000L)).thenReturn(true);
        FotoRecibida grande = new FotoRecibida(20_000_000L, null, () -> {
            throw new AssertionError("no se tenía que descargar");
        });

        Accion.Enviar r = (Accion.Enviar) procesador.procesarFoto(CHAT, MENSAJE_USUARIO, grande).get(0);

        assertThat(r.html()).startsWith("La imagen pesa más de 10 MB 🙏");
        verify(tickets, never()).leer(any(), any());
    }

    @Test
    void unaImagenQueNoSePuedeAbrirPideMandarlaComoFoto() {
        when(tickets.leer(FOTO, null)).thenThrow(new ImagenInvalidaException(
                ImagenInvalidaException.Motivo.FORMATO_NO_SOPORTADO, "heic"));

        assertThat(mandarFoto(null).html()).contains("Mandala como foto (no como archivo)");
    }

    @Test
    void sinIaElTicketNoSeLeeYSugiereEscribirlo() {
        when(tickets.leer(FOTO, null)).thenThrow(new IaNoDisponibleException("No pude consultar a la IA", null));

        assertThat(mandarFoto(null).html()).startsWith("No pude leer el ticket: No pude consultar a la IA.")
                .contains("super 8050");
    }

    @Test
    void siNoSePuedeDescargarLaFotoLoDice() {
        FotoRecibida rota = new FotoRecibida(5L, null, () -> {
            throw new java.io.IOException("timeout");
        });

        Accion.Enviar r = (Accion.Enviar) procesador.procesarFoto(CHAT, MENSAJE_USUARIO, rota).get(0);

        assertThat(r.html()).startsWith("No pude descargar la foto de Telegram");
    }

    @Test
    void cancelarUnTicketBorraSuFoto() {
        Movimiento cancelado = ticket("8050", SUPER, PRODUCTOS);
        cancelado.cancelar();
        when(movimientos.cancelar(ID)).thenReturn(cancelado);

        List<Accion> acciones = tocar("c:15");

        assertThat(edicion(acciones).html()).startsWith("❌ <b>Cancelado</b> · Supermercado Día");
        verify(tickets).borrarFoto(cancelado);
    }

    @Test
    void cancelarUnGastoDeTextoNoTocaFotos() {
        Movimiento cancelado = conCategoria("1500", KIOSCO);
        cancelado.cancelar();
        when(movimientos.cancelar(ID)).thenReturn(cancelado);

        tocar("c:15");

        verify(tickets, never()).borrarFoto(any());
    }

    @Test
    void unaFotoMientrasSeEditaUnCampoVuelveLaTarjetaAtrasYLeeElTicket() {
        Movimiento enEdicion = conCategoria("1500", KIOSCO);
        when(movimientos.buscar(ID)).thenReturn(enEdicion);
        tocar("em:15"); // la tarjeta espera el monto
        Movimiento leido = ticket("8050", SUPER, PRODUCTOS);
        ReflectionTestUtils.setField(leido, "id", 16L);
        when(tickets.leer(FOTO, null)).thenReturn(new TicketService.Leido(leido));

        List<Accion> acciones = procesador.procesarFoto(CHAT, MENSAJE_USUARIO, foto(null));

        assertThat(acciones).hasSize(2);
        assertThat(edicion(acciones).html()).startsWith("🧾 <b>Nuevo gasto</b>").doesNotContain("Escribí");
        assertThat(((Accion.Enviar) acciones.get(1)).html()).startsWith("🧾 <b>Ticket</b>");
    }

    // --- Ingresos ---

    @Test
    void sueldoArmaUnaTarjetaPendienteConGuardarYCancelar() {
        Movimiento pendiente = ingreso(ClaseIngreso.SUELDO, "380000", null);
        when(ingresos.crearPendiente(eq(ClaseIngreso.SUELDO), any(), any(), any(), eq(false), any(),
                eq("/sueldo 380.000"))).thenReturn(pendiente);

        Accion.Enviar tarjeta = (Accion.Enviar) procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "/sueldo 380.000")
                .get(0);

        assertThat(tarjeta.html()).startsWith("💵 <b>Nuevo ingreso</b>\n💰 <b>$380.000</b>\n🏷 Sueldo");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("g:15", "c:15");
        assertThat(tarjeta.tarjetaDeMovimiento()).isEqualTo(ID);
        verify(ingresos).crearPendiente(ClaseIngreso.SUELDO, new BigDecimal("380000"), null, null, false, AHORA,
                "/sueldo 380.000");
        verifyNoInteractions(interpretador);
    }

    @Test
    void ingresoExtraConDescripcionYEfectivoYAguinaldo() {
        when(ingresos.crearPendiente(any(), any(), any(), any(), anyBoolean(), any(), anyString()))
                .thenReturn(ingreso(ClaseIngreso.EXTRA, "5000", "Regalo"));

        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "/ingreso 5000 regalo en efectivo");
        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "/aguinaldo 190 mil");

        verify(ingresos).crearPendiente(ClaseIngreso.EXTRA, new BigDecimal("5000"), "regalo", null, true, AHORA,
                "/ingreso 5000 regalo en efectivo");
        verify(ingresos).crearPendiente(ClaseIngreso.AGUINALDO, new BigDecimal("190000"), null, null, false, AHORA,
                "/aguinaldo 190 mil");
    }

    @Test
    void guardarUnIngresoMuestraElTotalCobradoEnElMes() {
        Movimiento sueldo = ingreso(ClaseIngreso.SUELDO, "380000", null);
        sueldo.confirmar();
        when(movimientos.confirmar(ID))
                .thenReturn(new MovimientoService.Confirmacion(sueldo, new BigDecimal("405000")));

        Accion.Editar editada = edicion(tocar("g:15"));

        assertThat(editada.html()).startsWith("✅ <b>Guardado</b>\n💰 <b>$380.000</b>\n🏷 Sueldo")
                .endsWith("En octubre 2026 cobraste <b>$405.000</b> en total.");
    }

    @Test
    void sinMontoOMontoRaroMuestraUnEjemplo() {
        assertThat(texto("/sueldo")).contains("Decime el monto", "/sueldo 380.000 galicia");
        assertThat(texto("/ingreso arreglo de PC")).contains("No entendí el monto", "/ingreso 25000");
        verifyNoInteractions(ingresos);
    }

    private static Movimiento ingreso(ClaseIngreso clase, String monto, String descripcion) {
        return conId(Movimiento.ingreso(clase, new BigDecimal(monto), descripcion, null, null, AHORA, Origen.TEXTO,
                "x", AHORA), ID);
    }

    // --- Comandos ---

    private String texto(String mensaje) {
        return ((Accion.Enviar) procesador.procesarTexto(CHAT, MENSAJE_USUARIO, mensaje).get(0)).html();
    }

    @Test
    void comandos() {
        assertThat(texto("/start")).isEqualTo(ProcesadorMensajes.BIENVENIDA);
        assertThat(texto("/ayuda")).isEqualTo(ProcesadorMensajes.AYUDA);
        assertThat(texto("/AYUDA@gastito_bot")).isEqualTo(ProcesadorMensajes.AYUDA);
        assertThat(texto("/cualquiera")).contains("No conozco ese comando");
        verifyNoInteractions(interpretador);
    }

    @Test
    void dashboardMandaElLinkDeLaConfiguracionYTieneBotonEnElMenu() {
        assertThat(texto("/dashboard"))
                .contains("<a href=\"http://localhost:8090\">http://localhost:8090</a>")
                .contains("PC");
        assertThat(texto(MenuFijo.DASHBOARD)).isEqualTo(texto("/dashboard"));
        assertThat(MenuFijo.teclado().getKeyboard()).anySatisfy(fila -> assertThat(fila).anySatisfy(
                boton -> assertThat(boton.getText()).isEqualTo(MenuFijo.DASHBOARD)));
    }

    @Test
    void unComandoCancelaLaEdicionEnCurso() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");

        List<Accion> acciones = procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "/ayuda");

        assertThat(edicion(acciones).mensajeId()).isEqualTo(TARJETA);
        assertThat(acciones.get(1)).isInstanceOf(Accion.Enviar.class);
    }

    @Test
    void comandoHoy() {
        when(movimientos.hoy()).thenReturn(new MovimientoService.ResumenDia(List.of(), BigDecimal.ZERO));
        assertThat(texto("/hoy")).isEqualTo("Hoy no registraste gastos.");

        Movimiento m = confirmado("3700", KIOSCO);
        when(movimientos.hoy()).thenReturn(new MovimientoService.ResumenDia(List.of(m, m), new BigDecimal("7400")));
        assertThat(texto("/hoy")).isEqualTo("""
                <b>Gastos de hoy</b>
                • 🍬 Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42
                • 🍬 Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42

                Total: <b>$7.400</b>""");
    }

    @Test
    void comandoCostos() {
        when(usoIa.resumenMesActual()).thenReturn(new ResumenUsoIa(3, 6000, 250, new BigDecimal("0.00072500")));

        assertThat(texto("/costos")).isEqualTo("""
                <b>Uso de la IA en octubre 2026</b>
                • Llamadas: 3
                • Tokens: 6000 de entrada, 250 de salida
                • Costo: <b>US$ 0,000725</b>""");
    }

    @Test
    void comandoMesConBarras() {
        when(movimientos.mesActual()).thenReturn(new MovimientoService.ResumenMes(YearMonth.of(2026, 10), List.of(
                new TotalPorCategoria("Supermercado", "🛒", new BigDecimal("30000")),
                new TotalPorCategoria("Kiosco", "🍬", new BigDecimal("5200.50"))), new BigDecimal("35200.50")));

        assertThat(texto("/mes")).isEqualTo("""
                📅 <b>Octubre 2026</b>
                Total: <b>$35.200,50</b>

                <pre>🛒 Supermercado     $30.000 ▇▇▇▇▇▇▇▇▇▇▇▇
                🍬 Kiosco         $5.200,50 ▇▇</pre>""");
    }

    @Test
    void mesSinGastos() {
        when(movimientos.mesActual()).thenReturn(new MovimientoService.ResumenMes(YearMonth.of(2026, 10), List.of(),
                BigDecimal.ZERO));

        assertThat(texto("/mes")).isEqualTo("Octubre 2026: todavía no hay gastos guardados.");
    }

    // --- Menú fijo ---

    @Test
    void losBotonesDelMenuSonComandosYLasRespuestasLlevanElMenu() {
        when(movimientos.hoy()).thenReturn(new MovimientoService.ResumenDia(List.of(), BigDecimal.ZERO));
        when(usoIa.resumenMesActual()).thenReturn(new ResumenUsoIa(0, 0, 0, BigDecimal.ZERO));

        Accion.Enviar hoy = (Accion.Enviar) procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "📊 Hoy").get(0);
        assertThat(hoy.html()).isEqualTo("Hoy no registraste gastos.");
        assertThat(hoy.conMenu()).isTrue();

        assertThat(texto("💸 Costos IA")).contains("todavía no se usó la IA");
        assertThat(texto("❓ Ayuda")).isEqualTo(ProcesadorMensajes.AYUDA);
        assertThat(((Accion.Enviar) procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "/start").get(0)).conMenu())
                .isTrue();
        verifyNoInteractions(interpretador);
    }

    @Test
    void lasTarjetasNoLlevanElMenuSinoSusBotones() {
        Accion.Enviar tarjeta = mandarGasto("kiosco 1500", conCategoria("1500", KIOSCO), Confianza.ALTA, null);
        assertThat(tarjeta.conMenu()).isFalse();
        assertThat(tarjeta.teclado().isVacio()).isFalse();
    }
}
