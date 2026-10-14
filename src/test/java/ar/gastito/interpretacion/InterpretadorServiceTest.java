package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.CREDITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.EFECTIVO;
import static ar.gastito.interpretacion.CatalogoDePrueba.KIOSCO;
import static ar.gastito.interpretacion.CatalogoDePrueba.TRANSPORTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.Prompts;
import ar.gastito.movimiento.Tarjeta;

class InterpretadorServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);

    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final ClienteIa ia = mock(ClienteIa.class);
    private final Tarjeta tarjeta = mock(Tarjeta.class);
    private InterpretadorService interpretador;

    @BeforeEach
    void setUp() {
        Catalogo base = CatalogoDePrueba.crear();
        when(catalogos.cargar()).thenReturn(new Catalogo(base.categorias(), base.mediosPago(), base.cuentas(),
                Optional.of(tarjeta)));
        Clock clock = Clock.fixed(AHORA.withSecond(17).atZone(ARGENTINA).toInstant(), ARGENTINA);
        interpretador = new InterpretadorService(catalogos, new ParserSimple(),
                new ValidadorInterpretacion(new ResolvedorFechas()), ia, new Prompts(), clock);
    }

    @Test
    void losCasosObviosNoLlamanALaIa() {
        InterpretadorService.Resultado r = interpretador.interpretar("kiosco 1500");

        assertThat(r).isInstanceOf(InterpretadorService.Interpretado.class);
        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) r;
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
    void loDemasLoInterpretaLaIaConElCatalogoReal() {
        when(ia.extraer(anyString(), anyString(), eq(InterpretacionIa.class), eq(PropositoIa.TEXTO)))
                .thenReturn(new ClienteIa.Respuesta<>(new InterpretacionIa(true, "3700", "Pan de pancho y papas",
                        "Kiosco", "Efectivo", "", "", ""), 7L));

        InterpretadorService.Resultado r = interpretador.interpretar("3700 en pan de pancho y papas, efectivo");

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) r;
        assertThat(i.movimiento().descripcion()).isEqualTo("Pan de pancho y papas");
        assertThat(i.movimiento().medioPago()).isSameAs(EFECTIVO);
        assertThat(i.usoIaId()).isEqualTo(7L);

        ArgumentCaptor<String> instrucciones = ArgumentCaptor.forClass(String.class);
        verify(ia).extraer(instrucciones.capture(), eq("3700 en pan de pancho y papas, efectivo"),
                any(Class.class), eq(PropositoIa.TEXTO));
        assertThat(instrucciones.getValue())
                .contains("- Kiosco", "- Transporte / Moto", "- Tarjeta de crédito", "- Mercado Pago")
                .contains("martes 13/10/2026")
                .doesNotContain("{{");
    }

    @Test
    void siLaIaNoEncuentraElMontoHayQuePreguntarlo() {
        when(ia.extraer(anyString(), anyString(), eq(InterpretacionIa.class), eq(PropositoIa.TEXTO)))
                .thenReturn(new ClienteIa.Respuesta<>(new InterpretacionIa(true, "", "Nafta", "Transporte / Moto",
                        "Tarjeta de crédito", "", "ayer", ""), 8L));

        InterpretadorService.Resultado r = interpretador.interpretar("cargué nafta ayer con la tarjeta");

        assertThat(r).isInstanceOf(InterpretadorService.FaltaMonto.class);
        InterpretadorService.FaltaMonto f = (InterpretadorService.FaltaMonto) r;
        assertThat(f.sinMonto().categoria()).isSameAs(TRANSPORTE);
        assertThat(f.sinMonto().fechaHora().toLocalDate()).isEqualTo("2026-10-12");
        assertThat(f.usoIaId()).isEqualTo(8L);
    }

    @Test
    void mensajesQueNoSonGastos() {
        when(ia.extraer(anyString(), anyString(), eq(InterpretacionIa.class), eq(PropositoIa.TEXTO)))
                .thenReturn(new ClienteIa.Respuesta<>(new InterpretacionIa(false, "", "", "", "", "", "", ""), 9L));

        assertThat(interpretador.interpretar("hola!")).isInstanceOf(InterpretadorService.NoEsGasto.class);
    }
}
