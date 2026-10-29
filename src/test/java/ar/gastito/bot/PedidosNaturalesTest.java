package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.DEBITO;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SUPER;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.cuota.VistaPlanCuotas;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.juntada.EstadoParticipante;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.ticket.TicketService;

class PedidosNaturalesTest {

    static final int MENSAJE = 100;

    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final GastoFijoService fijos = mock(GastoFijoService.class);
    private final PlanCuotasService planes = mock(PlanCuotasService.class);
    private final JuntadaService juntadas = mock(JuntadaService.class);
    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final PedidosNaturales pedidos = new PedidosNaturales(movimientos, fijos, planes, juntadas, formato,
            new Tarjetas(formato), mock(TicketService.class));

    private static Movimiento pan() {
        Movimiento m = conId(Movimiento.pendiente(MovimientoInterpretado.conCategoria(new BigDecimal("3700"), "Pan",
                SUPER, null, null, null, AHORA), Origen.TEXTO, "3700 pan", AHORA), ID);
        m.confirmar();
        return m;
    }

    private static List<String> datos(Teclado t) {
        return t.botones().stream().map(Teclado.Boton::datos).toList();
    }

    @Test
    void corregirMuestraElCambioYSoloLoAplicaConElBoton() {
        when(movimientos.ultimoCargado()).thenReturn(Optional.of(pan()));
        ValidadorInterpretacion.Correccion c = new ValidadorInterpretacion.Correccion(
                ValidadorInterpretacion.Campo.MONTO, new BigDecimal("3500"), null, null, null, null);

        Accion.Enviar pregunta = (Accion.Enviar) pedidos.corregir(c, "");

        assertThat(pregunta.html()).startsWith("✏️ <b>¿Corrijo lo último que cargaste?</b>")
                .endsWith("💰 $3.700 → <b>$3.500</b>");
        assertThat(datos(pregunta.teclado())).containsExactly("uc:1", "un:0");
        verify(movimientos, never()).corregir(any(), any(), any(), any(), any(), any());

        Movimiento corregido = pan();
        corregido.corregir(new BigDecimal("3500"), null, null, null, null, null);
        when(movimientos.corregir(ID, new BigDecimal("3500"), null, null, null, null)).thenReturn(corregido);
        when(movimientos.buscar(ID)).thenReturn(corregido);

        List<Accion> acciones = pedidos.boton(MENSAJE, PedidosNaturales.APLICAR_CORRECCION, 1);

        assertThat(((Accion.Editar) acciones.get(0)).html()).startsWith("✅ <b>Corregido</b>").contains("$3.500");
        assertThat(((Accion.Editar) pedidos.boton(MENSAJE, PedidosNaturales.APLICAR_CORRECCION, 1).get(0)).html())
                .contains("ya no está pendiente");
    }

    @Test
    void corregirElMedio() {
        when(movimientos.ultimoCargado()).thenReturn(Optional.of(pan()));

        Accion.Enviar pregunta = (Accion.Enviar) pedidos.corregir(new ValidadorInterpretacion.Correccion(
                ValidadorInterpretacion.Campo.MEDIO, null, null, null, null, DEBITO), "");

        assertThat(pregunta.html()).endsWith("💳 Sin medio de pago → <b>Débito</b>");
    }

    @Test
    void borrarPreguntaAntesYSinNadaCargadoLoDice() {
        when(movimientos.ultimoCargado()).thenReturn(Optional.of(pan()));

        Accion.Enviar pregunta = (Accion.Enviar) pedidos.borrar("");

        assertThat(pregunta.html()).startsWith("🗑 <b>¿Borro lo último que cargaste?</b>");
        assertThat(datos(pregunta.teclado())).containsExactly("ub:15", "un:0");
        verify(movimientos, never()).anular(any());

        when(movimientos.ultimoCargado()).thenReturn(Optional.empty());
        assertThat(((Accion.Enviar) pedidos.borrar("")).html()).contains("Todavía no cargaste nada");
        assertThat(((Accion.Editar) pedidos.boton(MENSAJE, PedidosNaturales.NADA, 0).get(0)).html())
                .isEqualTo("👌 No cambié nada.");
    }

    @Test
    void bajaDeUnFijoSoloSiSeSabeCual() {
        when(fijos.parecido("Netflix")).thenReturn(Optional.of(new VistaFijo(4L, "Netflix", new BigDecimal("11999"),
                new BigDecimal("11999"), "Suscripciones", "📺", null, 7, Frecuencia.MENSUAL, false, null, true, true,
                null)));

        Accion.Enviar pregunta = (Accion.Enviar) pedidos.bajaDeFijo("Netflix", "").orElseThrow();

        assertThat(pregunta.html()).startsWith("🗑 <b>¿Doy de baja el fijo Netflix?</b>");
        assertThat(datos(pregunta.teclado())).containsExactly("uf:4", "un:0");
        assertThat(pedidos.bajaDeFijo("Spotify", "")).isEmpty();
    }

    @Test
    void cancelarCuotasBuscaLaCompraPorLoQueDijo() {
        when(planes.activos()).thenReturn(List.of(
                new VistaPlanCuotas(3L, "Zapatillas Nike", new BigDecimal("84000"), 6, new BigDecimal("14000"),
                        "Ropa", "👕", null, 2, 4, LocalDate.of(2026, 11, 1), true),
                new VistaPlanCuotas(4L, "Heladera", new BigDecimal("600000"), 12, new BigDecimal("50000"),
                        "Casa", "🏠", null, 1, 11, LocalDate.of(2026, 11, 1), true)));

        Accion.Enviar pregunta = (Accion.Enviar) pedidos.cancelarCuotas("las zapatillas", "").orElseThrow();

        assertThat(pregunta.html()).startsWith("🗑 <b>¿Cancelo las cuotas de Zapatillas Nike?</b>");
        assertThat(datos(pregunta.teclado())).containsExactly("cup:3", "un:0");
        assertThat(pedidos.cancelarCuotas("el televisor", "")).isEmpty();
    }

    @Test
    void perdonarUsaElBotonDeLaJuntada() {
        VistaJuntada.Persona pablo = new VistaJuntada.Persona(21L, "Pablo", new BigDecimal("8000"), BigDecimal.ZERO,
                new BigDecimal("8000"), EstadoParticipante.PENDIENTE, false);
        when(juntadas.conDeudasPendientes()).thenReturn(List.of(new VistaJuntada(ID, 2L, "Asado", "Salidas", AHORA,
                new BigDecimal("48000"), new BigDecimal("8000"), new BigDecimal("8000"), 6, null, true,
                List.of(pablo))));

        Accion.Enviar pregunta = (Accion.Enviar) pedidos.perdonar("pablo", null, "").orElseThrow();

        assertThat(pregunta.html()).startsWith("🚫 <b>¿Doy por perdida la parte de Pablo</b> ($8.000) de Asado?");
        assertThat(datos(pregunta.teclado())).containsExactly("jx:15:21", "un:0");
        assertThat(pedidos.perdonar("Sofi", null, "")).isEmpty();
    }

    @Test
    void coincideIgnoraTildesYPalabrasCortas() {
        assertThat(PedidosNaturales.coincide("las zapatillas", "Zapatillas Nike")).isTrue();
        assertThat(PedidosNaturales.coincide("la heladera", "Heladéra")).isTrue();
        assertThat(PedidosNaturales.coincide("de la", "De La Rúa")).isFalse();
    }
}
