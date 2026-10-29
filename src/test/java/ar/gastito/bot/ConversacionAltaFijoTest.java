package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.DEBITO;
import static ar.gastito.bot.DatosDePrueba.EFECTIVO;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.ParserFijo;
import ar.gastito.interpretacion.PedidoFijo;

class ConversacionAltaFijoTest {

    static final long CHAT = 7;
    static final int MENSAJE = 100;
    static final Categoria SEGUROS = new Categoria(9L, "Seguros", "seguro,seguros", "Seguros", "🛡️");
    static final MedioPago CREDITO = conId(new MedioPago("Tarjeta de crédito", "tarjeta,credito"), 3L);
    static final TarjetaCredito MASTERCARD = mock(TarjetaCredito.class);

    private final GastoFijoService servicio = mock(GastoFijoService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final ConversacionAltaFijo conversacion = new ConversacionAltaFijo(servicio, catalogos,
            new FormatoMensajes(RELOJ), new ParserFijo());

    @BeforeEach
    void setUp() {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(SEGUROS), List.of(EFECTIVO, DEBITO, CREDITO),
                List.of(), Optional.of(MASTERCARD)));
        when(servicio.primerCobro(anyInt())).thenReturn(LocalDate.of(2026, 11, 4));
        when(servicio.parecido(any())).thenReturn(Optional.empty());
    }

    /** El seguro de la moto del audio: con tarjeta de crédito, sin día. */
    private static PedidoFijo seguro(BigDecimal monto, Integer dia) {
        return new PedidoFijo("Seguro moto", monto, SEGUROS, List.of(), CREDITO, null, MASTERCARD, dia, false, false,
                "seguro de la moto");
    }

    private static List<String> datos(Teclado t) {
        return t.botones().stream().map(Teclado.Boton::datos).toList();
    }

    private Accion.Editar tocar(String accion, Long elegido) {
        return (Accion.Editar) conversacion.boton(CHAT, MENSAJE, accion, 1, elegido).get(0);
    }

    @Test
    void sinDiaMuestraLoEntendidoYPreguntaElDiaConOtroYNoSe() {
        Accion.Enviar tarjeta = conversacion.nueva(CHAT, seguro(new BigDecimal("32000"), null), "");

        assertThat(tarjeta.html()).isEqualTo("""
                📌 <b>Nuevo gasto fijo</b>
                Seguro moto · ≈ $32.000 · mensual
                💳 Tarjeta de crédito · 🛡️ Seguros
                📅 Día: sin definir

                📅 <b>¿Qué día del mes se paga?</b>""");
        assertThat(datos(tarjeta.teclado())).containsExactly("ad:1:1", "ad:1:5", "ad:1:10", "ad:1:15", "ad:1:20",
                "ad:1:25", "ad:1:99", "ad:1:0", "fz:1");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::texto).contains("✏️ Otro", "🤷 No sé");
        verify(servicio, never()).alta(any());
    }

    @Test
    void conNoSeSeGuardaSinDiaYConElAlias() {
        conversacion.nueva(CHAT, seguro(new BigDecimal("32000"), null), "");

        Accion.Editar lista = tocar(ConversacionAltaFijo.DIA, ConversacionAltaFijo.NO_SE);
        assertThat(lista.html()).contains("📅 Día: sin definir").doesNotContain("¿Qué día");
        assertThat(datos(lista.teclado())).containsExactly("fa:1", "ae:1", "fz:1");

        when(servicio.alta(any())).thenReturn(new VistaFijo(7L, "Seguro moto", new BigDecimal("32000.00"),
                new BigDecimal("32000.00"), "Seguros", "🛡️", "MasterCard", null, Frecuencia.MENSUAL, false,
                "seguro de la moto", false, true, null));
        Accion.Editar guardado = tocar(ConversacionAltaFijo.GUARDAR, null);

        ArgumentCaptor<GastoFijoService.NuevoFijo> alta = ArgumentCaptor.forClass(GastoFijoService.NuevoFijo.class);
        verify(servicio).alta(alta.capture());
        assertThat(alta.getValue().diaDelMes()).isNull();
        assertThat(alta.getValue().alias()).isEqualTo("seguro de la moto");
        assertThat(alta.getValue().tarjeta()).isSameAs(MASTERCARD);
        assertThat(guardado.html()).startsWith("✅ <b>Gasto fijo guardado</b>\nSeguro moto: ≈ $32.000 por mes.")
                .contains("sin recordatorio de vencimiento");
    }

    @Test
    void conOtroSeEscribeElDia() {
        conversacion.nueva(CHAT, seguro(new BigDecimal("32000"), null), "");

        assertThat(tocar(ConversacionAltaFijo.DIA, ConversacionAltaFijo.OTRO).html())
                .endsWith("📅 <b>¿Qué día del mes se paga?</b> Escribime el número.");
        assertThat(conversacion.esperando(CHAT)).isTrue();

        Accion.Enviar conDia = conversacion.texto(CHAT, "el 4").orElseThrow();

        assertThat(conDia.html()).contains("📅 Día 4 · primer cobro: miércoles 4/11");
        assertThat(datos(conDia.teclado())).containsExactly("fa:1", "ae:1", "fz:1");
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void sinMontoLoPideComoTextoYAceptaNoSe() {
        Accion.Enviar tarjeta = conversacion.nueva(CHAT, seguro(null, 4), "");

        assertThat(tarjeta.html()).contains("monto sin definir").endsWith(
                "💰 <b>¿Cuánto se paga, más o menos?</b> Escribímelo o decímelo en un audio.");
        assertThat(datos(tarjeta.teclado())).containsExactly("an:1", "fz:1");
        assertThat(conversacion.texto(CHAT, "unos 32 lucas").orElseThrow().html())
                .contains("Seguro moto · ≈ $32.000 · mensual");

        conversacion.nueva(CHAT, seguro(null, 4), "");
        assertThat(conversacion.boton(CHAT, MENSAJE, ConversacionAltaFijo.MONTO_NO_SE, 2, null).get(0))
                .satisfies(a -> assertThat(((Accion.Editar) a).html()).contains("Seguro moto · sin monto"));
    }

    @Test
    void unTextoQueNoEsElDatoSeDejaPasar() {
        conversacion.nueva(CHAT, seguro(null, 4), "");

        assertThat(conversacion.texto(CHAT, "¿cuánto gasté hoy?")).isEmpty();
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void sinMedioSeEligeConLosMismosBotonesQueUnGasto() {
        PedidoFijo sinMedio = new PedidoFijo("Gimnasio", new BigDecimal("25000"), SEGUROS, List.of(), null, null,
                null, 5, false, false);

        Accion.Enviar tarjeta = conversacion.nueva(CHAT, sinMedio, "");

        assertThat(tarjeta.html()).endsWith("💳 <b>¿Cómo se paga?</b>");
        assertThat(datos(tarjeta.teclado())).containsExactly("am:1:1", "am:1:2", "am:1:3", "am:1:0", "fz:1");
        assertThat(tocar(ConversacionAltaFijo.MEDIO, 3L).html()).contains("💳 Tarjeta de crédito");
    }

    @Test
    void siYaExisteUnoParecidoAvisaYOfreceActualizarElMonto() {
        when(servicio.parecido("Seguro moto", "seguro de la moto")).thenReturn(Optional.of(new VistaFijo(7L,
                "Seguro moto", new BigDecimal("30000"), new BigDecimal("30000"), "Seguros", "🛡️", null, 4,
                Frecuencia.MENSUAL, false, null, true, true, null)));

        Accion.Enviar aviso = conversacion.nueva(CHAT, seguro(new BigDecimal("32000"), 4), "");

        assertThat(aviso.html()).endsWith(
                "⚠️ Ya tenés el fijo <b>Seguro moto</b> ($30.000). ¿Actualizo el monto o es otro?");
        assertThat(datos(aviso.teclado())).containsExactly("av:1:7", "ao:1", "fz:1");

        when(servicio.actualizarMonto(7L, new BigDecimal("32000"))).thenReturn(new VistaFijo(7L, "Seguro moto",
                new BigDecimal("32000"), new BigDecimal("32000"), "Seguros", "🛡️", null, 4, Frecuencia.MENSUAL, false,
                null, true, true, null));
        assertThat(tocar(ConversacionAltaFijo.ACTUALIZAR_EXISTENTE, 7L).html())
                .isEqualTo("✅ <b>Seguro moto</b> ahora es de ≈ $32.000.");
        verify(servicio, never()).alta(any());
    }

    @Test
    void esOtroSigueConElAlta() {
        when(servicio.parecido("Seguro moto", "seguro de la moto")).thenReturn(Optional.of(new VistaFijo(7L,
                "Seguro", null, null, "Seguros", "🛡️", null, null, Frecuencia.MENSUAL, false, null, false, true,
                null)));
        assertThat(conversacion.nueva(CHAT, seguro(new BigDecimal("32000"), 4), "").html())
                .endsWith("⚠️ Ya tenés el fijo <b>Seguro</b>. ¿Actualizo el monto o es otro?");

        Accion.Editar siguiente = tocar(ConversacionAltaFijo.ES_OTRO, null);

        assertThat(datos(siguiente.teclado())).containsExactly("fa:1", "ae:1", "fz:1");
    }

    @Test
    void elAtajoDelComandoUsaElParserYNingunaPreguntaPideUnFormato() {
        assertThat(conversacion.desdeComando(CHAT, "seguro 32900 tarjeta día 4")).isPresent();
        assertThat(conversacion.desdeComando(CHAT, "el seguro de la moto")).isEmpty();
        assertThat(ConversacionAltaFijo.preguntaInicial()).doesNotContain("/fijo");
        assertThat(ConversacionAltaFijo.monto("$32.500 pesos")).contains(new BigDecimal("32500"));
        assertThat(ConversacionAltaFijo.dia("día 31")).contains(31);
        assertThat(ConversacionAltaFijo.dia("el 40")).isEmpty();
    }
}
