package ar.gastito.dashboard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import ar.gastito.catalogo.Categoria;
import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.dashboard.EdicionMovimientos.Alcance;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoEspecialException;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

class EdicionMovimientosTest {

    static final long ID = 7;
    static final LocalDateTime COMPRA = LocalDateTime.of(2026, 10, 21, 16, 42);
    static final Categoria KIOSCO = new Categoria(1L, "Kiosco", "kiosco", null, "🍬");

    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final JuntadaService juntadas = mock(JuntadaService.class);
    private final PlanCuotasService cuotas = mock(PlanCuotasService.class);
    private final EdicionMovimientos edicion = new EdicionMovimientos(movimientos, juntadas, cuotas);

    private static <T> T conId(T entidad, long id) {
        ReflectionTestUtils.setField(entidad, "id", id);
        return entidad;
    }

    private static Movimiento guardado() {
        Movimiento m = conId(Movimiento.pendiente(MovimientoInterpretado.conCategoria(new BigDecimal("3700"),
                "Pan de pancho y papas", KIOSCO, null, null, null, COMPRA), Origen.TEXTO, "x", COMPRA), ID);
        m.confirmar();
        return m;
    }

    private Movimiento existe(Movimiento m) {
        when(movimientos.buscar(ID)).thenReturn(m);
        return m;
    }

    @Test
    void editarCambiaElDiaYDejaLaHora() {
        Movimiento m = existe(guardado());
        when(movimientos.editar(any(), any(), any(), any(), any(), any(), any())).thenReturn(m);

        edicion.editar(ID, new BigDecimal("3500"), LocalDate.of(2026, 10, 20), "Pancho", 1L, null, 2L);

        verify(movimientos).editar(ID, new BigDecimal("3500"), "Pancho", LocalDateTime.of(2026, 10, 20, 16, 42), 1L,
                null, 2L);
    }

    @Test
    void unGastoComunSeBorraSolo() {
        existe(guardado());

        edicion.borrar(ID, Alcance.GASTO);

        verify(movimientos).borrar(ID);
    }

    @Test
    void unaJuntadaSeBorraEntera() {
        Movimiento m = guardado();
        ReflectionTestUtils.setField(m, "juntada", true);
        existe(m);

        edicion.borrar(ID, Alcance.JUNTADA);

        verify(juntadas).borrarEntera(ID);
        verify(movimientos, never()).borrar(any());
    }

    @Test
    void unaJuntadaNoSeBorraComoUnGastoComunYLoExplica() {
        Movimiento m = guardado();
        ReflectionTestUtils.setField(m, "juntada", true);
        existe(m);

        assertThatThrownBy(() -> edicion.borrar(ID, Alcance.GASTO)).isInstanceOf(MovimientoEspecialException.class)
                .hasMessage("Es una juntada: se borra entera, con lo que te deben");
        verify(juntadas, never()).borrarEntera(any());
    }

    @Test
    void unaCuotaCancelaElPlanDesdeEsaCuota() {
        Movimiento m = guardado();
        ReflectionTestUtils.setField(m, "planCuotasId", 3L);
        ReflectionTestUtils.setField(m, "cuotaActual", 2);
        ReflectionTestUtils.setField(m, "cuotaTotal", 6);
        existe(m);

        assertThatThrownBy(() -> edicion.borrar(ID, Alcance.GASTO))
                .hasMessage("Es la cuota 2 de 6: se cancela el plan desde esta cuota");
        edicion.borrar(ID, Alcance.CUOTAS);

        verify(cuotas).cancelarDesde(ID);
    }

    @Test
    void elPagoDeUnFijoSeBorraConAvisoYElFijoVuelveAFaltar() {
        Movimiento m = guardado();
        ReflectionTestUtils.setField(m, "gastoFijoId", 4L);
        ReflectionTestUtils.setField(m, "fijoPeriodo", LocalDate.of(2026, 10, 1));
        existe(m);

        assertThatThrownBy(() -> edicion.borrar(ID, Alcance.GASTO)).hasMessageContaining("vuelve a figurar");
        edicion.borrar(ID, Alcance.FIJO);

        verify(movimientos).borrar(ID);
    }

    @Test
    void unGastoComunNoSeBorraComoJuntada() {
        existe(guardado());

        assertThatThrownBy(() -> edicion.borrar(ID, Alcance.JUNTADA)).isInstanceOf(MovimientoEspecialException.class);
        verify(juntadas, never()).borrarEntera(any());
    }

    @Test
    void unoPendienteONoGuardadoNoExisteParaElDashboard() {
        existe(conId(Movimiento.pendiente(MovimientoInterpretado.conCategoria(BigDecimal.TEN, "x", KIOSCO, null,
                null, null, COMPRA), Origen.TEXTO, "x", COMPRA), ID));

        assertThatThrownBy(() -> edicion.borrar(ID, Alcance.GASTO)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> edicion.editar(ID, BigDecimal.TEN, null, null, 1L, null, null))
                .isInstanceOf(NoSuchElementException.class);
    }
}
