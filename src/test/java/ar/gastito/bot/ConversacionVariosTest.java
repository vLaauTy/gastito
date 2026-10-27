package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.KIOSCO;
import static ar.gastito.bot.DatosDePrueba.SUPER;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static ar.gastito.bot.DatosDePrueba.conId;
import static ar.gastito.bot.DatosDePrueba.dudoso;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

class ConversacionVariosTest {

    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final GastoFijoService fijos = mock(GastoFijoService.class);
    private ConversacionVarios conversacion;

    @BeforeEach
    void setUp() {
        FormatoMensajes formato = new FormatoMensajes(DatosDePrueba.RELOJ);
        conversacion = new ConversacionVarios(movimientos, fijos, formato, new Tarjetas(formato));
    }

    private Movimiento fijoVinculado(String monto, long gastoFijoId) {
        Movimiento m = conId(Movimiento.cobroDeFijo(MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Luz",
                KIOSCO, null, null, null, AHORA), gastoFijoId, LocalDate.of(2026, 10, 1), AHORA), 1L);
        return m;
    }

    @Test
    void laTarjetaMuestraCadaGastoConSuEstadoYAvisaSiYaEstabaCargado() {
        Movimiento completo = conId(conCategoria("1500", KIOSCO), 1L);
        Movimiento incompleto = conId(dudoso("2000", KIOSCO, SUPER), 2L);
        Movimiento deFijo = fijoVinculado("90000", 7L);
        Movimiento anterior = conId(conCategoria("88000", KIOSCO), 99L);
        when(fijos.yaCargadoEstePeriodo(7L, YearMonth.of(2026, 10))).thenReturn(Optional.of(anterior));

        Accion.Enviar tarjeta = conversacion.tarjeta(List.of(completo, incompleto, deFijo), "");

        assertThat(tarjeta.html()).contains("🧾 <b>3 gastos</b>")
                .contains("✅ Pan de pancho y papas .... $1.500")
                .contains("❓ Pan de pancho y papas .... $2.000")
                .contains("✅ Luz .... $90.000 (fijo)")
                .contains("⚠️ Ya cargaste luz este mes ($88.000). ¿Es otro pago?")
                .contains("Los que tienen ❓ los armo en una tarjeta aparte");
    }

    @Test
    void guardarTodoConfirmaLosCompletosYDejaAparteLosQueFaltanCompletar() {
        Movimiento completo = conId(conCategoria("1500", KIOSCO), 1L);
        Movimiento incompleto = conId(dudoso("2000", KIOSCO, SUPER), 2L);
        when(movimientos.buscar(1L)).thenReturn(completo);
        when(movimientos.buscar(2L)).thenReturn(incompleto);
        Accion.Enviar tarjetaIncompleta = conversacion.tarjeta(List.of(completo, incompleto), "");
        String[] datos = tarjetaIncompleta.teclado().botones().get(0).datos().split(":");

        List<Accion> acciones = conversacion.boton(100, datos[0], Long.parseLong(datos[1]));

        verify(movimientos).confirmar(1L);
        verify(movimientos, never()).confirmar(2L);
        assertThat(acciones).hasSize(2);
        assertThat(((Accion.Editar) acciones.get(0)).html()).contains("1 gasto(s) guardado(s)")
                .contains("Los demás quedaron abajo");
        assertThat(acciones.get(1)).isInstanceOf(Accion.Enviar.class);
    }

    @Test
    void cancelarNoGuardaNada() {
        Movimiento completo = conId(conCategoria("1500", KIOSCO), 1L);
        Accion.Enviar tarjeta = conversacion.tarjeta(List.of(completo), "");
        String[] datos = tarjeta.teclado().botones().get(1).datos().split(":");

        List<Accion> acciones = conversacion.boton(100, datos[0], Long.parseLong(datos[1]));

        verify(movimientos).cancelar(1L);
        verify(movimientos, never()).confirmar(any());
        assertThat(acciones).singleElement().satisfies(a ->
                assertThat(((Accion.Editar) a).html()).contains("Cancelado"));
    }

    @Test
    void unGrupoYaResueltoAvisaQueNoEstaPendiente() {
        assertThat(conversacion.boton(100, ConversacionVarios.GUARDAR_TODO, 999)).singleElement()
                .isEqualTo(new Accion.Avisar("Ese grupo ya no está pendiente"));
    }
}
