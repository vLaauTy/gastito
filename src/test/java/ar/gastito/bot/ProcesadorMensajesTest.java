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
import static ar.gastito.bot.DatosDePrueba.confirmado;
import static ar.gastito.bot.DatosDePrueba.dudoso;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.ia.ResumenUsoIa;
import ar.gastito.interpretacion.Catalogo;
import ar.gastito.interpretacion.CatalogoService;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoIncompletoException;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TotalPorCategoria;

class ProcesadorMensajesTest {

    static final long CHAT = 42;
    static final int TARJETA = 100;
    static final int MENSAJE_USUARIO = 101;

    private final InterpretadorService interpretador = mock(InterpretadorService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final RegistroUsoIa usoIa = mock(RegistroUsoIa.class);
    private ProcesadorMensajes procesador;

    @BeforeEach
    void setUp() {
        FormatoMensajes formato = new FormatoMensajes(RELOJ);
        procesador = new ProcesadorMensajes(interpretador, movimientos, catalogos, usoIa, new ResolvedorFechas(),
                new Tarjetas(formato), formato, new GraficoMes(formato), RELOJ);
        when(catalogos.cargar()).thenReturn(new Catalogo(CATEGORIAS, MEDIOS, List.of(), Optional.empty()));
    }

    /** El usuario manda un gasto y el interpretador devuelve lo que se guarda como pendiente. */
    private Accion.Enviar mandarGasto(String texto, Movimiento pendiente, Confianza confianza, Long usoIaId) {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(pendiente.getMonto(),
                pendiente.getDescripcion(), pendiente.getCategoria(), pendiente.getCandidatas(),
                pendiente.getMedioPago(), null, null, pendiente.getFechaHora());
        when(interpretador.interpretar(texto))
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
        when(interpretador.interpretar("500")).thenReturn(new InterpretadorService.NoEsGasto());
        procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "500");
        verify(movimientos, never()).cambiarMonto(anyLong(), any());
    }

    @Test
    void siEnVezDelValorMandaOtroGastoLaTarjetaVuelveYSeProcesaElNuevo() {
        when(movimientos.buscar(ID)).thenReturn(conCategoria("3700", KIOSCO));
        tocar("em:15");
        Movimiento otro = DatosDePrueba.conId(conCategoria("2000", SUPER), 16L);
        when(interpretador.interpretar("super 2000")).thenReturn(new InterpretadorService.Interpretado(
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
        when(interpretador.interpretar(anyString()))
                .thenThrow(new IaNoDisponibleException("No pude consultar a la IA", null));

        Accion.Enviar r = (Accion.Enviar) procesador.procesarTexto(CHAT, MENSAJE_USUARIO, "algo largo").get(0);

        assertThat(r.html()).contains("No pude consultar a la IA").contains("kiosco 1500");
        verify(movimientos, never()).crearPendiente(any(), any(), anyString());
    }

    @Test
    void mensajeQueNoEsGasto() {
        when(interpretador.interpretar("hola")).thenReturn(new InterpretadorService.NoEsGasto());

        assertThat(texto("hola")).contains("No encontré un gasto");
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
