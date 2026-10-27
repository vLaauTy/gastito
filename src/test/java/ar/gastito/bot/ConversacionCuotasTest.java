package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SALIDAS;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.cuota.VistaPlanCuotas;
import ar.gastito.interpretacion.PedidoCuotas;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;

class ConversacionCuotasTest {

    static final int MENSAJE = 100;

    private final PlanCuotasService servicio = mock(PlanCuotasService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final Tarjetas tarjetas = new Tarjetas(formato);
    private final ConversacionCuotas conversacion = new ConversacionCuotas(servicio, movimientos, tarjetas, formato);

    private Movimiento cuota(int actual, int total) {
        Movimiento m = conCategoria("14000", SALIDAS);
        m.prepararCuota(9L, actual, total);
        return m;
    }

    @Test
    void crearDelegaEnElServicioYDevuelveElMovimientoActualizado() {
        Movimiento pendiente = conCategoria("84000", SALIDAS);
        Movimiento actualizado = cuota(1, 6);
        PedidoCuotas pedido = new PedidoCuotas(6, new BigDecimal("84000.00"), new BigDecimal("14000.00"));
        when(movimientos.buscar(ID)).thenReturn(actualizado);

        Movimiento resultado = conversacion.crear(pendiente, pedido);

        verify(servicio).crear(ID, 6, new BigDecimal("84000.00"));
        assertThat(resultado).isSameAs(actualizado);
    }

    @Test
    void guardadaSinCuotasSiguientesNoAgregaLaLineaDelPlan() {
        Movimiento m = cuota(1, 6);
        m.confirmar();
        when(servicio.alGuardar(ID)).thenReturn(List.of());

        List<Accion> acciones = conversacion.guardada(MENSAJE, new MovimientoService.Confirmacion(m,
                new BigDecimal("14000")));

        Accion.Editar editar = (Accion.Editar) acciones.get(0);
        assertThat(editar.html()).doesNotContain("Plan de");
    }

    @Test
    void guardadaConCuotasSiguientesAgregaLaFechaDeLaProxima() {
        Movimiento m = cuota(1, 6);
        m.confirmar();
        Movimiento siguiente = cuota(2, 6);
        when(servicio.alGuardar(ID)).thenReturn(List.of(siguiente));

        List<Accion> acciones = conversacion.guardada(MENSAJE, new MovimientoService.Confirmacion(m,
                new BigDecimal("14000")));

        Accion.Editar editar = (Accion.Editar) acciones.get(0);
        assertThat(editar.html()).contains("Plan de 6 cuotas");
    }

    @Test
    void botonCancelaYDevuelveLaListaActualizada() {
        VistaPlanCuotas cancelado = new VistaPlanCuotas(9L, "Zapatillas", new BigDecimal("84000"), 6,
                new BigDecimal("14000"), "Ropa", "👟", "Mastercard", 2, 4, null, false);
        when(servicio.cancelar(9L)).thenReturn(cancelado);
        when(servicio.activos()).thenReturn(List.of());

        List<Accion> acciones = conversacion.boton(MENSAJE, 9L);

        verify(servicio).cancelar(9L);
        Accion.Editar editar = (Accion.Editar) acciones.get(0);
        assertThat(editar.html()).isEqualTo("🗂 No tenés compras en cuotas.");
    }

    @Test
    void listaSinPlanesActivosLoDice() {
        when(servicio.activos()).thenReturn(List.of());

        List<Accion> acciones = conversacion.lista();

        Accion.Enviar enviar = (Accion.Enviar) acciones.get(0);
        assertThat(enviar.html()).isEqualTo("🗂 No tenés compras en cuotas.");
    }

    @Test
    void listaConPlanesActivosMuestraElProgresoYLaProximaCuota() {
        VistaPlanCuotas plan = new VistaPlanCuotas(9L, "Zapatillas", new BigDecimal("84000"), 6,
                new BigDecimal("14000"), "Ropa", "👟", "Mastercard", 2, 4, LocalDate.of(2026, 12, 20), true);
        when(servicio.activos()).thenReturn(List.of(plan));

        List<Accion> acciones = conversacion.lista();

        Accion.Enviar enviar = (Accion.Enviar) acciones.get(0);
        assertThat(enviar.html()).contains("Zapatillas").contains("cuota 2 de 6").contains("$14.000")
                .contains("$84.000");
    }
}
