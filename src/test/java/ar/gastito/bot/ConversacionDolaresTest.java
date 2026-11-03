package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.DEBITO;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.KIOSCO;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.MedioPago;
import ar.gastito.dolar.CompraEnDolaresService;
import ar.gastito.dolar.CotizacionDolar;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

class ConversacionDolaresTest {

    static final long CHAT = 42;
    static final int MENSAJE = 100;
    static final MedioPago CREDITO = conId(new MedioPago(MedioPago.TARJETA_DE_CREDITO, "tarjeta,credito"), 3L);

    private final CompraEnDolaresService compras = mock(CompraEnDolaresService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final ConversacionDolares conversacion = new ConversacionDolares(compras, movimientos, formato);

    /** Los movimientos que llegaron a la tarjeta de siempre. */
    private final List<Movimiento> seguidos = new ArrayList<>();
    private final Function<Movimiento, Accion.Enviar> seguir = m -> {
        seguidos.add(m);
        return Accion.Enviar.texto("tarjeta de " + m.getId());
    };

    /** "Pagué 20 dólares de Steam": pendiente, todavía con el número en dólares como monto. */
    private static Movimiento steam(MedioPago medio) {
        return conId(Movimiento.pendiente(MovimientoInterpretado.conCategoria(new BigDecimal("20"), "Steam", KIOSCO,
                medio, null, null, AHORA), Origen.TEXTO, "pagué 20 dólares de steam", AHORA), ID);
    }

    private static Movimiento enDolares(BigDecimal cotizacion) {
        Movimiento m = steam(CREDITO);
        m.pasarADolares(new BigDecimal("20"), cotizacion);
        return m;
    }

    private static String texto(Accion accion) {
        return switch (accion) {
            case Accion.Enviar e -> e.html();
            case Accion.Editar e -> e.html();
            default -> "";
        };
    }

    @Test
    void conCotizacionSigueConLaTarjetaDeSiempre() {
        Movimiento m = enDolares(new BigDecimal("1500"));
        when(compras.pasarADolares(ID, new BigDecimal("20"))).thenReturn(m);

        Accion.Enviar enviado = conversacion.nueva(CHAT, steam(CREDITO), "", seguir);

        assertThat(enviado.html()).isEqualTo("tarjeta de " + ID);
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void sinCotizacionLaPideConNoSe() {
        when(compras.pasarADolares(ID, new BigDecimal("20"))).thenReturn(enDolares(null));

        Accion.Enviar enviado = conversacion.nueva(CHAT, steam(null), "", seguir);

        assertThat(enviado.html()).contains("<b>US$ 20</b> en Steam con la tarjeta")
                .contains("¿A cuánto está el dólar tarjeta?");
        assertThat(enviado.teclado().filas().getFirst().getFirst().datos())
                .isEqualTo(ConversacionDolares.NO_SE + ":" + ID);
        assertThat(conversacion.esperando(CHAT)).isTrue();
        assertThat(seguidos).isEmpty();
    }

    @Test
    void laCotizacionQueEscribeEstimaYSigueConLaTarjeta() {
        Movimiento m = enDolares(null);
        when(compras.pasarADolares(ID, new BigDecimal("20"))).thenReturn(m);
        conversacion.nueva(CHAT, steam(null), "", seguir);
        when(compras.cargarCotizacion(new BigDecimal("1500"))).thenAnswer(i -> {
            m.estimarPesos(new BigDecimal("1500"));
            return new CompraEnDolaresService.CotizacionCargada(new CotizacionDolar(new BigDecimal("1500"), AHORA),
                    List.of(m));
        });
        when(movimientos.buscar(ID)).thenReturn(m);

        List<Accion> acciones = conversacion.texto(CHAT, "$1.500", seguir).orElseThrow();

        assertThat(texto(acciones.getFirst())).contains("el dólar tarjeta queda a <b>$1.500</b>");
        assertThat(seguidos).containsExactly(m);
        assertThat(m.getMonto()).isEqualByComparingTo("30000");
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void siLoQueEscribeNoEsUnaCotizacionSeProcesaNormal() {
        when(compras.pasarADolares(ID, new BigDecimal("20"))).thenReturn(enDolares(null));
        conversacion.nueva(CHAT, steam(null), "", seguir);

        assertThat(conversacion.texto(CHAT, "kiosco 1500 y alfajor", seguir)).isEmpty();
        verify(compras, never()).cargarCotizacion(any());
    }

    @Test
    void conNoSeQuedaSinEstimarYSigue() {
        Movimiento m = enDolares(null);
        when(movimientos.buscar(ID)).thenReturn(m);

        List<Accion> acciones = conversacion.boton(CHAT, MENSAJE, ConversacionDolares.NO_SE, ID, null, seguir,
                x -> null);

        assertThat(texto(acciones.getFirst())).contains("US$ 20 ≈ sin estimar");
        assertThat(seguidos).containsExactly(m);
    }

    @Test
    void conOtroMedioPreguntaSiFueConLaTarjetaOEranPesos() {
        Accion.Enviar enviado = conversacion.nueva(CHAT, steam(DEBITO), "", seguir);

        assertThat(enviado.html()).contains("con Débito").contains("solo con la tarjeta de crédito");
        assertThat(enviado.teclado().filas()).extracting(f -> f.getFirst().datos()).containsExactly(
                ConversacionDolares.CON_TARJETA + ":" + ID, ConversacionDolares.EN_PESOS + ":" + ID,
                Tarjetas.CANCELAR + ":" + ID);
        verify(compras, never()).pasarADolares(any(), any());
    }

    @Test
    void eranPesosSigueComoUnGastoEnPesos() {
        Movimiento m = steam(DEBITO);
        when(movimientos.buscar(ID)).thenReturn(m);

        conversacion.boton(CHAT, MENSAJE, ConversacionDolares.EN_PESOS, ID, null, seguir, x -> null);

        assertThat(seguidos).containsExactly(m);
        assertThat(m.isEnDolares()).isFalse();
        verify(compras, never()).pasarADolares(any(), any());
    }

    @Test
    void conUnaCotizacionDichaSinCompraPendienteSoloLaGuarda() {
        when(compras.cargarCotizacion(new BigDecimal("1620"))).thenReturn(new CompraEnDolaresService.CotizacionCargada(
                new CotizacionDolar(new BigDecimal("1620"), AHORA), List.of()));

        List<Accion> acciones = conversacion.cotizacion(CHAT, new BigDecimal("1620"), "", seguir);

        assertThat(acciones).hasSize(1);
        assertThat(texto(acciones.getFirst())).contains("$1.620");
    }

    @Test
    void sinValorMuestraLaCotizacionActual() {
        when(compras.referencia()).thenReturn(Optional.of(new CotizacionDolar(new BigDecimal("1500"), AHORA)));

        assertThat(texto(conversacion.cotizacion(CHAT, null, "", null).getFirst()))
                .contains("está a <b>$1.500</b> (desde el 13/10)");
    }

    @Test
    void loDeSteamSalioPoneCorregirLaCompra() {
        Movimiento compra = conId(enDolares(new BigDecimal("1500")), 7L);
        Movimiento nuevo = conId(Movimiento.pendiente(MovimientoInterpretado.conCategoria(new BigDecimal("31200"),
                "Steam", KIOSCO, null, null, null, AHORA), Origen.TEXTO, "lo de steam salió 31.200", AHORA), 8L);
        when(compras.estimadaParecida(nuevo)).thenReturn(Optional.of(compra));

        Accion.Enviar propuesta = conversacion.siCorrigeUnaCompra(nuevo).orElseThrow();

        assertThat(propuesta.html()).contains("US$ 20 ≈ $30.000 (estimado)");
        assertThat(propuesta.teclado().filas().getFirst().getFirst().texto())
                .isEqualTo("🔄 Corregir el monto ($30.000 → $31.200)");
        assertThat(propuesta.teclado().filas().getFirst().getFirst().datos())
                .isEqualTo(ConversacionDolares.CORREGIR + ":8:7");
    }
}
