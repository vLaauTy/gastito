package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.KIOSCO;
import static ar.gastito.bot.DatosDePrueba.PRODUCTOS;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SUPER;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static ar.gastito.bot.DatosDePrueba.conId;
import static ar.gastito.bot.DatosDePrueba.confirmado;
import static ar.gastito.bot.DatosDePrueba.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.RepetidosService;
import ar.gastito.ticket.TicketService;

class ConversacionRepetidosTest {

    private final RepetidosService repetidos = mock(RepetidosService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final TicketService tickets = mock(TicketService.class);
    private final GastoFijoService fijos = mock(GastoFijoService.class);
    private final ConversacionRepetidos conversacion = new ConversacionRepetidos(repetidos, movimientos, tickets,
            new Tarjetas(new FormatoMensajes(RELOJ)), fijos, new FormatoMensajes(RELOJ));

    private final Movimiento existente = conId(confirmado("8050", SUPER), 7L);

    @Test
    void sinParecidoVaLaTarjetaDeSiempre() {
        Movimiento nuevo = conCategoria("1500", KIOSCO);
        when(repetidos.buscar(nuevo)).thenReturn(Optional.empty());

        assertThat(conversacion.siEsRepetido(nuevo)).isEmpty();
    }

    @Test
    void conParecidoVanLosDosLadoALadoConSusBotones() {
        Movimiento nuevo = ticket("8050", SUPER, PRODUCTOS);
        when(repetidos.buscar(nuevo)).thenReturn(Optional.of(existente));

        Accion.Enviar tarjeta = conversacion.siEsRepetido(nuevo).orElseThrow();

        assertThat(tarjeta.html()).startsWith("🔁 <b>¿Ya lo tenías registrado?</b>\nEncontré un gasto muy parecido:")
                .contains("<b>Ya registrado</b> · ✍️ texto\n💰 <b>$8.050</b>")
                .contains("<b>Nuevo</b> · 📷 ticket\n💰 <b>$8.050</b> · Supermercado Día")
                .contains("3 productos");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("ru:15:7", "rd:15");
    }

    @Test
    void unirDejaUnoSoloYBorraLaFotoQueSobra() {
        when(repetidos.unir(15L, 7L)).thenReturn(new RepetidosService.Union(existente, "tickets/2026/10/15.jpg"));

        List<Accion> acciones = conversacion.unir(100, 15L, 7L);

        assertThat(((Accion.Editar) acciones.get(0)).html()).startsWith("🔗 <b>Unidos: quedó uno solo</b>");
        verify(tickets).borrarArchivoDeFoto("tickets/2026/10/15.jpg");
    }

    @Test
    void comprarDeNuevoGuardaElNuevoSiEstaCompleto() {
        Movimiento nuevo = conCategoria("1500", KIOSCO);
        when(movimientos.buscar(15L)).thenReturn(nuevo);
        when(movimientos.confirmar(15L)).thenReturn(new MovimientoService.Confirmacion(confirmado("1500", KIOSCO),
                new BigDecimal("3000")));

        List<Accion> acciones = conversacion.deNuevo(100, 15L).orElseThrow();

        assertThat(((Accion.Editar) acciones.get(0)).html()).startsWith("✅ <b>Guardado</b>");
    }

    @Test
    void comprarDeNuevoUnTicketOAlgoIncompletoVuelveALaTarjetaDeSiempre() {
        when(movimientos.buscar(15L)).thenReturn(ticket("8050", SUPER, PRODUCTOS));
        assertThat(conversacion.deNuevo(100, 15L)).isEmpty();

        when(movimientos.buscar(15L)).thenReturn(conCategoria("1500", null));
        assertThat(conversacion.deNuevo(100, 15L)).isEmpty();
        verify(movimientos, never()).confirmar(15L);
    }

    @Test
    void unirConElCobroDeUnFijoAvisaSiAumento() {
        when(repetidos.unir(15L, 7L)).thenReturn(new RepetidosService.Union(existente, null, true));
        when(fijos.alConfirmar(7L)).thenReturn(Optional.of(new GastoFijoService.Aumento("Seguro",
                new BigDecimal("32900"), new BigDecimal("36100"), new BigDecimal("9.7"))));

        Accion.Editar editar = (Accion.Editar) conversacion.unir(100, 15L, 7L).get(0);

        assertThat(editar.html()).endsWith("📈 Seguro subió de $32.900 a $36.100 (+9,7%).");
    }

    @Test
    void unirConUnGastoComunNoMiraLosFijos() {
        when(repetidos.unir(15L, 7L)).thenReturn(new RepetidosService.Union(existente, null));

        conversacion.unir(100, 15L, 7L);

        verify(fijos, never()).alConfirmar(any());
    }
}
