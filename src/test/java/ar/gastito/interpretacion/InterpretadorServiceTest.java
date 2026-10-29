package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.CREDITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.EFECTIVO;
import static ar.gastito.interpretacion.CatalogoDePrueba.KIOSCO;
import static ar.gastito.interpretacion.CatalogoDePrueba.SUPER;
import static ar.gastito.interpretacion.CatalogoDePrueba.TRANSPORTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.DescripcionConfirmada;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

class InterpretadorServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);

    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final MovimientoRepository repositorio = mock(MovimientoRepository.class);
    private final ClienteIa ia = mock(ClienteIa.class);
    private final TarjetaCredito tarjeta = mock(TarjetaCredito.class);
    private InterpretadorService interpretador;

    @BeforeEach
    void setUp() {
        Catalogo base = CatalogoDePrueba.crear();
        when(catalogos.cargar()).thenReturn(new Catalogo(base.categorias(), base.mediosPago(), base.cuentas(),
                Optional.of(tarjeta)));
        when(movimientos.ultimasConfirmaciones()).thenReturn(List.of());
        when(repositorio.descripcionesConfirmadas()).thenReturn(List.of());
        Clock clock = Clock.fixed(AHORA.withSecond(17).atZone(ARGENTINA).toInstant(), ARGENTINA);
        interpretador = new InterpretadorService(catalogos, new ParserSimple(),
                new ValidadorInterpretacion(new ResolvedorFechas()), new HistorialCategorias(repositorio),
                new ResolvedorFechas(), movimientos, ia, new Prompts(), clock);
    }

    private void yaConfirmo(DescripcionConfirmada... confirmados) {
        when(repositorio.descripcionesConfirmadas()).thenReturn(List.of(confirmados));
    }

    // --- Aprendizaje desde el historial ---

    @Test
    void siYaLoConfirmoEnUnaCategoriaNoLlamaALaIa() {
        yaConfirmo(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()));

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "3700 en pan de pancho y papas, efectivo");

        assertThat(i.confianza()).isEqualTo(Confianza.ALTA);
        assertThat(i.movimiento().categoria()).isSameAs(SUPER);
        assertThat(i.movimiento().descripcion()).isEqualTo("Pan de pancho y papas");
        assertThat(i.movimiento().monto()).isEqualByComparingTo("3700");
        assertThat(i.movimiento().medioPago()).isSameAs(EFECTIVO);
        assertThat(i.movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(i.usoIaId()).isNull();
        verifyNoInteractions(ia);
    }

    @Test
    void elHistorialTambienResuelveAyer() {
        yaConfirmo(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()));

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "ayer 3700 pan de pancho y papas");

        assertThat(i.movimiento().fechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 12, 12, 0));
        verifyNoInteractions(ia);
    }

    @Test
    void despuesDeLaIaElHistorialCorrigeLaCategoria() {
        yaConfirmo(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()));
        iaResponde(new InterpretacionIa("GASTO", "3700", "Pan de pancho y papas", List.of("Kiosco", "Supermercado"),
                "media", "", "", "", ""), 7L);

        // "unos" y "compré" no los saca el separador, pero la IA devuelve la descripción limpia.
        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "compré unos panes de pancho con papas por 3700");

        assertThat(i.confianza()).isEqualTo(Confianza.ALTA);
        assertThat(i.movimiento().categoria()).isSameAs(SUPER);
        assertThat(i.movimiento().candidatas()).isEmpty();
        assertThat(i.usoIaId()).isEqualTo(7L);
    }

    @Test
    void siElHistorialNoEsUnanimeSePregunta() {
        yaConfirmo(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()),
                new DescripcionConfirmada("Pan de pancho y papas", KIOSCO.getId()));
        iaResponde(new InterpretacionIa("GASTO", "3700", "Pan de pancho y papas", List.of("Supermercado", "Kiosco"),
                "media", "", "", "", ""), 7L);

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "3700 en pan de pancho y papas");

        assertThat(i.confianza()).isEqualTo(Confianza.MEDIA);
        assertThat(i.movimiento().categoria()).isNull();
        assertThat(i.movimiento().candidatas()).containsExactly(SUPER, KIOSCO);
    }

    @Test
    void laTranscripcionDeUnAudioRegistraLaIaComoAudio() {
        when(ia.extraer(anyString(), anyString(), eq(InterpretacionIa.class), eq(PropositoIa.AUDIO)))
                .thenReturn(new ClienteIa.Respuesta<>(
                        new InterpretacionIa("GASTO", "3700", "Pan de pancho y papas", List.of("Supermercado"), "alta",
                                "Efectivo", "", "", ""), 9L));

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "Gasto 3700 en pan de pancho y papas, efectivo", PropositoIa.AUDIO);

        assertThat(i.usoIaId()).isEqualTo(9L);
        verify(ia).extraer(anyString(), eq("Gasto 3700 en pan de pancho y papas, efectivo"),
                eq(InterpretacionIa.class), eq(PropositoIa.AUDIO));
    }

    @Test
    void unAudioQueEntiendeElParserSimpleNoUsaLaIa() {
        interpretador.interpretar("kiosco 1500", PropositoIa.AUDIO);

        verifyNoInteractions(ia);
    }

    private void iaResponde(InterpretacionIa respuesta, long usoIaId) {
        when(ia.extraer(anyString(), anyString(), eq(InterpretacionIa.class), eq(PropositoIa.TEXTO)))
                .thenReturn(new ClienteIa.Respuesta<>(respuesta, usoIaId));
    }

    private static Movimiento confirmado(String descripcion, Categoria categoria) {
        Movimiento m = Movimiento.pendiente(MovimientoInterpretado.conCategoria(BigDecimal.TEN, descripcion,
                categoria, null, null, null, AHORA), Origen.TEXTO, descripcion, AHORA);
        m.confirmar();
        return m;
    }

    @Test
    void losCasosObviosNoLlamanALaIaYTienenConfianzaAlta() {
        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "kiosco 1500");

        assertThat(i.confianza()).isEqualTo(Confianza.ALTA);
        assertThat(i.movimiento().categoria()).isSameAs(KIOSCO);
        assertThat(i.movimiento().monto()).isEqualByComparingTo("1500");
        assertThat(i.movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(i.usoIaId()).isNull();
        verifyNoInteractions(ia);
    }

    @Test
    void elParserSimpleTambienAsociaLaTarjeta() {
        InterpretadorService.Interpretado i =
                (InterpretadorService.Interpretado) interpretador.interpretar("nafta 12000 tarjeta");
        assertThat(i.movimiento().medioPago()).isSameAs(CREDITO);
        assertThat(i.movimiento().tarjeta()).isSameAs(tarjeta);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unGastoDudosoVieneConCandidatasYSinCategoria() {
        iaResponde(new InterpretacionIa("GASTO", "3700", "Pan de pancho y papas", List.of("Supermercado", "Kiosco"),
                "media", "Efectivo", "", "", ""), 7L);

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "3700 en pan de pancho y papas, efectivo");

        assertThat(i.confianza()).isEqualTo(Confianza.MEDIA);
        assertThat(i.movimiento().categoria()).isNull();
        assertThat(i.movimiento().candidatas()).containsExactly(SUPER, KIOSCO);
        assertThat(i.movimiento().medioPago()).isSameAs(EFECTIVO);
        assertThat(i.usoIaId()).isEqualTo(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void elPromptLlevaLasDescripcionesDeLasCategoriasYLosEjemplos() {
        when(movimientos.ultimasConfirmaciones()).thenReturn(List.of(
                confirmado("Pan de pancho y papas", SUPER), confirmado("Alfajor", KIOSCO)));
        iaResponde(new InterpretacionIa("GASTO", "100", "x", List.of("Kiosco"), "alta", "", "", "", ""), 7L);

        interpretador.interpretar("algo que no entiende el parser simple");

        ArgumentCaptor<String> instrucciones = ArgumentCaptor.forClass(String.class);
        verify(ia).extraer(instrucciones.capture(), eq("algo que no entiende el parser simple"),
                any(Class.class), eq(PropositoIa.TEXTO));
        assertThat(instrucciones.getValue())
                .contains("- Kiosco: Golosinas, bebidas, snacks, compras chicas al paso.")
                .contains("- Transporte / Moto: Nafta, colectivo, SUBE, Uber.")
                .contains("- \"Pan de pancho y papas\" → Supermercado", "- \"Alfajor\" → Kiosco")
                .contains("- Tarjeta de crédito", "- Mercado Pago")
                .contains("martes 13/10/2026")
                .doesNotContain("{{");
    }

    @Test
    void sinConfirmacionesElPromptLoDice() {
        assertThat(ContextoIa.ejemplos(List.of())).isEqualTo("(todavía no hay)");
    }

    @Test
    void siLaIaNoEncuentraElMontoVieneVacio() {
        iaResponde(new InterpretacionIa("GASTO", "", "Nafta", List.of("Transporte / Moto"), "alta",
                "Tarjeta de crédito", "", "ayer", ""), 8L);

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "cargué nafta ayer con la tarjeta");

        assertThat(i.movimiento().monto()).isNull();
        assertThat(i.movimiento().categoria()).isSameAs(TRANSPORTE);
        assertThat(i.movimiento().fechaHora().toLocalDate()).isEqualTo("2026-10-12");
    }

    @Test
    void mensajesQueNoSonGastos() {
        iaResponde(new InterpretacionIa("OTRO", "", "", List.of(), "", "", "", "", ""), 9L);

        assertThat(interpretador.interpretar("hola!")).isInstanceOf(InterpretadorService.NoEsGasto.class);
    }

    @Test
    void unaPreguntaObviaNoLlamaALaIa() {
        InterpretadorService.Resultado r = interpretador.interpretar("¿Cuánto gasté en kiosco este mes?");

        assertThat(r).isEqualTo(new InterpretadorService.Pregunta("¿Cuánto gasté en kiosco este mes?", null));
        verifyNoInteractions(ia);
    }

    @Test
    void conMontoDecideLaIa() {
        iaResponde(new InterpretacionIa("GASTO", "9000", "Nafta", List.of("Transporte / Moto"), "alta", "", "",
                "", ""), 9L);

        assertThat(interpretador.interpretar("¿cuánto salió la nafta? 9000"))
                .isInstanceOf(InterpretadorService.Interpretado.class);
    }

    @Test
    void unaPreguntaQueReconoceLaIaYUnCasoDudoso() {
        iaResponde(new InterpretacionIa("PREGUNTA", "", "", List.of(), "", "", "", "", ""), 9L);
        assertThat(interpretador.interpretar("lo del kiosco de este mes"))
                .isEqualTo(new InterpretadorService.Pregunta("lo del kiosco de este mes", 9L));

        iaResponde(new InterpretacionIa("DUDOSO", "3700", "Pan", List.of("Supermercado"), "media", "", "", "",
                ""), 10L);
        InterpretadorService.Resultado r = interpretador.interpretar("pan 3700?");
        assertThat(r).isInstanceOf(InterpretadorService.Dudoso.class);
        assertThat(((InterpretadorService.Dudoso) r).comoGasto().movimiento().monto()).isEqualByComparingTo("3700");
        assertThat(((InterpretadorService.Dudoso) r).comoGasto().usoIaId()).isEqualTo(10L);
    }

    // --- Lenguaje natural para todo ---

    private static InterpretacionIa json(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, InterpretacionIa.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unAudioConErroresDeTranscripcionDaDeAltaElFijoEnLaMismaLlamada() {
        // "arreglame" en vez de "agregame", el monto con palabras: lo resuelve la IA (simulada) en una sola llamada.
        String escuchado = "arreglame un gasto fijo que es el seguro de la moto, que es aproximadamente treinta "
                + "y dos lucas, que es todos los meses y lo pago con tarjeta de crédito";
        when(ia.extraer(anyString(), eq(escuchado), eq(InterpretacionIa.class), eq(PropositoIa.AUDIO)))
                .thenReturn(new ClienteIa.Respuesta<>(json("""
                        {"intencion": "ALTA_FIJO", "monto": "32000", "descripcion": "Seguro moto",
                         "categorias": ["Transporte / Moto"], "confianza": "media",
                         "medioPago": "Tarjeta de crédito", "fijoAlias": "seguro de la moto"}"""), 4L));

        InterpretadorService.AltaFijo a = (InterpretadorService.AltaFijo) interpretador.interpretar(escuchado,
                PropositoIa.AUDIO);

        assertThat(a.pedido().nombre()).isEqualTo("Seguro moto");
        assertThat(a.pedido().monto()).isEqualByComparingTo("32000");
        assertThat(a.pedido().tarjeta()).isSameAs(tarjeta);
        assertThat(a.pedido().alias()).isEqualTo("seguro de la moto");
        assertThat(a.usoIaId()).isEqualTo(4L);
        verify(ia, times(1)).extraer(anyString(), anyString(), any(), any());
    }

    @Test
    void elPromptEsperaErroresDeWhisperYConoceTodasLasIntenciones() {
        String prompt = new Prompts().cargar(InterpretadorService.PROMPT, java.util.Map.of("categorias", "",
                "mediosPago", "", "cuentas", "", "ejemplos", "", "hoy", ""));
        assertThat(prompt).contains("lucas", "mil quinientos", "arreglame");
        for (Intencion i : Intencion.values()) {
            assertThat(prompt).as("el prompt explica " + i).contains(i.name());
        }
    }

    @Test
    void lasOtrasIntencionesLleganComoOrden() {
        iaResponde(json("{\"intencion\": \"CORREGIR\", \"campo\": \"MONTO\", \"monto\": \"3500\"}"), 6L);

        InterpretadorService.Orden o = (InterpretadorService.Orden) interpretador.interpretar(
                "el último era 3500, no 3700");

        assertThat(o.orden()).isInstanceOf(ValidadorInterpretacion.Correccion.class);
        assertThat(o.usoIaId()).isEqualTo(6L);
    }

    @Test
    void unPedidoDeListaObvioNoLlamaALaIa() {
        InterpretadorService.Orden o = (InterpretadorService.Orden) interpretador.interpretar(
                "¿Qué fijos me faltan pagar?");

        assertThat(o.orden()).isEqualTo(new ValidadorInterpretacion.Ver(ValidadorInterpretacion.Lista.FIJOS));
        assertThat(o.usoIaId()).isNull();
        verifyNoInteractions(ia);
    }

    @Test
    void elegirUnaOpcionDeUnDudosoNoVuelveALlamarALaIa() {
        iaResponde(json("""
                {"intencion": "DUDOSO", "monto": "32000", "descripcion": "Seguro", "categorias": ["Otros"],
                 "opciones": ["ALTA_FIJO", "GASTO"]}"""), 8L);

        InterpretadorService.Dudoso d = (InterpretadorService.Dudoso) interpretador.interpretar("el seguro 32 mil");
        assertThat(d.opciones()).containsExactly(Intencion.ALTA_FIJO, Intencion.GASTO);

        InterpretadorService.AltaFijo a = (InterpretadorService.AltaFijo) interpretador.reinterpretar(d,
                Intencion.ALTA_FIJO, "el seguro 32 mil");

        assertThat(a.pedido().nombre()).isEqualTo("Seguro");
        assertThat(a.pedido().monto()).isEqualByComparingTo("32000");
        assertThat(a.usoIaId()).isEqualTo(8L);
        verify(ia, times(1)).extraer(anyString(), anyString(), any(), any());
    }
}
