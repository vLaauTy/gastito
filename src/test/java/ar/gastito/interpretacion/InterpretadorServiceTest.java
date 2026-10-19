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

import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.Prompts;
import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.DescripcionConfirmada;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TarjetaCredito;

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
        iaResponde(new InterpretacionIa(true, "3700", "Pan de pancho y papas", List.of("Kiosco", "Supermercado"),
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
        iaResponde(new InterpretacionIa(true, "3700", "Pan de pancho y papas", List.of("Supermercado", "Kiosco"),
                "media", "", "", "", ""), 7L);

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "3700 en pan de pancho y papas");

        assertThat(i.confianza()).isEqualTo(Confianza.MEDIA);
        assertThat(i.movimiento().categoria()).isNull();
        assertThat(i.movimiento().candidatas()).containsExactly(SUPER, KIOSCO);
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
        iaResponde(new InterpretacionIa(true, "3700", "Pan de pancho y papas", List.of("Supermercado", "Kiosco"),
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
        iaResponde(new InterpretacionIa(true, "100", "x", List.of("Kiosco"), "alta", "", "", "", ""), 7L);

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
        assertThat(InterpretadorService.ejemplos(List.of())).isEqualTo("(todavía no hay)");
    }

    @Test
    void siLaIaNoEncuentraElMontoVieneVacio() {
        iaResponde(new InterpretacionIa(true, "", "Nafta", List.of("Transporte / Moto"), "alta",
                "Tarjeta de crédito", "", "ayer", ""), 8L);

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) interpretador.interpretar(
                "cargué nafta ayer con la tarjeta");

        assertThat(i.movimiento().monto()).isNull();
        assertThat(i.movimiento().categoria()).isSameAs(TRANSPORTE);
        assertThat(i.movimiento().fechaHora().toLocalDate()).isEqualTo("2026-10-12");
    }

    @Test
    void mensajesQueNoSonGastos() {
        iaResponde(new InterpretacionIa(false, "", "", List.of(), "", "", "", "", ""), 9L);

        assertThat(interpretador.interpretar("hola!")).isInstanceOf(InterpretadorService.NoEsGasto.class);
    }
}
