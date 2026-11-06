package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.config.GastitoProperties;
import ar.gastito.inversion.InversionService;
import ar.gastito.inversion.PanelInversiones;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;

/** Las inversiones en el bot, con Cocos (id 10, sin posiciones) y Galicia (id 1). */
class ConversacionInversionesTest {

    static final long CHAT = 42;
    static final long COCOS = 10;
    static final LocalDate HOY = LocalDate.now(RELOJ);

    private final InversionService inversiones = mock(InversionService.class);
    private final PanelInversiones panel = mock(PanelInversiones.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final ConversacionInversiones conversacion = new ConversacionInversiones(inversiones, panel, catalogos,
            new FormatoInversiones(new FormatoMensajes(RELOJ)), RELOJ);

    Cuenta cocos;
    Cuenta galicia;

    private static <T> T conId(T entidad, long id) {
        ReflectionTestUtils.setField(entidad, "id", id);
        return entidad;
    }

    static PanelInversiones.EstadoCuenta estado(String valor, long dias) {
        BigDecimal v = new BigDecimal(valor);
        BigDecimal puesto = new BigDecimal("850000");
        return new PanelInversiones.EstadoCuenta(COCOS, "Cocos", null, 7, puesto, v, v.subtract(puesto), null,
                HOY.minusDays(dias), dias, List.of(new PanelInversiones.Tenencia(null, "Cocos", puesto, v,
                        v.subtract(puesto), null, false, HOY.minusDays(dias))), BigDecimal.ZERO, v, List.of());
    }

    @BeforeEach
    void catalogo() {
        cocos = conId(Cuenta.deInversion("Cocos", null, 7), COCOS);
        galicia = conId(new Cuenta("Galicia", "galicia"), 1);
        when(inversiones.cuentas()).thenReturn(List.of(cocos));
        when(inversiones.posiciones()).thenReturn(List.of());
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(), List.of(), List.of(galicia), List.of()));
        when(panel.cuenta(COCOS)).thenReturn(estado("850000", 7));
    }

    private static Accion.Enviar enviar(List<Accion> acciones) {
        return (Accion.Enviar) acciones.getFirst();
    }

    @Test
    void recordatorioConIgualQueAntes() {
        Accion.Enviar pregunta = conversacion.recordatorio(CHAT,
                new InversionService.Recordatorio(COCOS, "Cocos", new BigDecimal("850000"), HOY.minusDays(7), 7));

        assertThat(pregunta.html()).contains("¿Cuánto vale hoy Cocos?", "última: $850.000 hace 7 días");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::texto, Teclado.Boton::datos)
                .contains(org.assertj.core.groups.Tuple.tuple("🟰 Igual que antes", "ii:10"));
        assertThat(conversacion.esperando(CHAT)).isTrue();

        List<Accion> respuesta = conversacion.boton(CHAT, 5, ConversacionInversiones.IGUAL, COCOS, null);

        verify(inversiones).igualQueAntes(COCOS, HOY);
        assertThat(((Accion.Editar) respuesta.getFirst()).html()).contains("Cocos vale $850.000");
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void elRecordatorioSeContestaConElMonto() {
        conversacion.recordatorio(CHAT,
                new InversionService.Recordatorio(COCOS, "Cocos", new BigDecimal("850000"), HOY.minusDays(7), 7));
        when(panel.cuenta(COCOS)).thenReturn(estado("870000", 0));

        Optional<List<Accion>> respuesta = conversacion.texto(CHAT, "870.000");

        verify(inversiones).valuar(COCOS, null, new BigDecimal("870000"), HOY);
        assertThat(enviar(respuesta.orElseThrow()).html()).contains("Cocos vale $870.000", "+$20.000");
    }

    @Test
    void siNoContestaUnMontoSeOlvidaYSigueNormal() {
        conversacion.recordatorio(CHAT,
                new InversionService.Recordatorio(COCOS, "Cocos", new BigDecimal("850000"), HOY.minusDays(7), 7));

        assertThat(conversacion.texto(CHAT, "kiosco 1500")).isEmpty();
        assertThat(conversacion.esperando(CHAT)).isFalse();
        verify(inversiones, never()).valuar(anyLong(), any(), any(), any());
    }

    @Test
    void noPreguntarMas() {
        when(inversiones.cambiarRecordatorio(COCOS, null)).thenReturn(cocos);

        List<Accion> respuesta = conversacion.boton(CHAT, 5, ConversacionInversiones.NO_PREGUNTAR, COCOS, null);

        verify(inversiones).cambiarRecordatorio(COCOS, null);
        assertThat(((Accion.Editar) respuesta.getFirst()).html()).contains("no te pregunto más");
    }

    @Test
    void elAporteConOrigenSeConfirmaYEsEntreCuentas() {
        Accion.Enviar tarjeta = enviar(conversacion.intentar(CHAT, "pasé 200.000 de Galicia a Cocos", Origen.TEXTO, "")
                .orElseThrow());
        assertThat(tarjeta.html()).contains("Aporte a Cocos", "$200.000 desde Galicia", "no cuenta como gasto");
        verify(inversiones, never()).aportar(anyLong(), any(), any(), any(), any(), any(), any());

        Movimiento m = Movimiento.entreCuentas(new BigDecimal("200000"), "Aporte a Cocos", galicia, cocos, null,
                LocalDateTime.now(RELOJ), Origen.TEXTO, null, LocalDateTime.now(RELOJ));
        when(inversiones.aportar(eq(COCOS), isNull(), any(), eq(1L), any(), eq(Origen.TEXTO), any())).thenReturn(m);
        String guardar = tarjeta.teclado().botones().getFirst().datos();
        List<Accion> guardado = conversacion.boton(CHAT, 7, ConversacionInversiones.GUARDAR,
                Long.parseLong(guardar.split(":")[1]), null);

        assertThat(((Accion.Editar) guardado.getFirst()).html()).contains("Aporte guardado");
        assertThat(((Accion.Editar) guardado.getFirst()).teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("↩️ Deshacer");
    }

    @Test
    void sinOrigenPreguntaDeDondeSalio() {
        Accion.Enviar tarjeta = enviar(conversacion.intentar(CHAT, "puse 200 mil en Cocos", Origen.AUDIO, "")
                .orElseThrow());

        assertThat(tarjeta.html()).contains("¿De dónde salió la plata?");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🏦 Galicia", "💵 Efectivo", "❌ Cancelar");

        Movimiento m = Movimiento.entreCuentas(new BigDecimal("200000"), "Aporte a Cocos", null, cocos, null,
                LocalDateTime.now(RELOJ), Origen.AUDIO, null, LocalDateTime.now(RELOJ));
        when(inversiones.aportar(eq(COCOS), isNull(), any(), isNull(), any(), eq(Origen.AUDIO), any())).thenReturn(m);
        String efectivo = tarjeta.teclado().botones().get(1).datos();
        String[] partes = efectivo.split(":");
        conversacion.boton(CHAT, 7, partes[0], Long.parseLong(partes[1]), Long.parseLong(partes[2]));

        verify(inversiones).aportar(eq(COCOS), isNull(), eq(new BigDecimal("200000")), isNull(), any(),
                eq(Origen.AUDIO), any());
    }

    @Test
    void unGastoNoEsDeInversiones() {
        assertThat(conversacion.intentar(CHAT, "kiosco 1500", Origen.TEXTO, "")).isEmpty();
    }

    @Test
    void elRecordatorioQueLlegaQuedaAnotado() {
        GastitoBot bot = mock(GastitoBot.class);
        GastitoProperties properties = mock(GastitoProperties.class);
        when(properties.telegram()).thenReturn(new GastitoProperties.Telegram("t", "u", CHAT));
        when(inversiones.recordatoriosPendientes()).thenReturn(List.of(
                new InversionService.Recordatorio(COCOS, "Cocos", new BigDecimal("850000"), HOY.minusDays(7), 7)));
        when(bot.enviarAlUsuario(any())).thenReturn(true);

        new InversionesProgramadas(inversiones, conversacion, bot, properties).revisar();

        verify(inversiones).recordatorioEnviado(COCOS);
        assertThat(conversacion.esperando(CHAT)).isTrue();
    }
}
