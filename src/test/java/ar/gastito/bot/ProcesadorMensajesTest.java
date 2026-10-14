package ar.gastito.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.ia.ResumenUsoIa;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.MedioPago;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TotalPorCategoria;

class ProcesadorMensajesTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);
    static final long CHAT = 42;

    private final InterpretadorService interpretador = mock(InterpretadorService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final RegistroUsoIa usoIa = mock(RegistroUsoIa.class);
    private ProcesadorMensajes procesador;

    private final Categoria kiosco = new Categoria("Kiosco", "kiosco");
    private final MedioPago efectivo = new MedioPago("Efectivo", "efectivo");
    private final MovimientoInterpretado panYPapas = new MovimientoInterpretado(new BigDecimal("3700"),
            "Pan de pancho y papas", kiosco, efectivo, null, null, AHORA);

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        procesador = new ProcesadorMensajes(interpretador, movimientos, usoIa, new FormatoMensajes(clock), clock);
        Movimiento pendiente = mock(Movimiento.class);
        when(pendiente.getId()).thenReturn(15L);
        when(movimientos.crearPendiente(any(), any(), anyString())).thenReturn(pendiente);
    }

    @Test
    void muestraLoQueEntendioConLosTresBotones() {
        when(interpretador.interpretar("3700 en pan de pancho y papas, efectivo"))
                .thenReturn(new InterpretadorService.Interpretado(panYPapas, 7L));

        Respuesta r = procesador.procesarTexto(CHAT, "3700 en pan de pancho y papas, efectivo");

        assertThat(r.texto()).isEqualTo("""
                Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42
                ¿Lo guardo así?""");
        assertThat(r.botones()).extracting(Respuesta.Boton::texto).containsExactly("Guardar", "Editar", "Cancelar");
        assertThat(r.botones()).extracting(Respuesta.Boton::datos).containsExactly("g:15", "e:15", "c:15");
        verify(movimientos).crearPendiente(panYPapas, Origen.TEXTO, "3700 en pan de pancho y papas, efectivo");
        verify(usoIa).asociarMovimiento(7L, 15L);
    }

    @Test
    void sinIaNoAsociaUso() {
        when(interpretador.interpretar("kiosco 1500")).thenReturn(new InterpretadorService.Interpretado(panYPapas, null));

        procesador.procesarTexto(CHAT, "kiosco 1500");

        verify(usoIa, never()).asociarMovimiento(any(), any());
    }

    @Test
    void siFaltaElMontoLoPreguntaYCompletaConElSiguienteMensaje() {
        MovimientoInterpretado sinMonto = panYPapas.conMonto(null);
        when(interpretador.interpretar("pan y papas en efectivo"))
                .thenReturn(new InterpretadorService.FaltaMonto(sinMonto, 8L));

        Respuesta pregunta = procesador.procesarTexto(CHAT, "pan y papas en efectivo");
        assertThat(pregunta.texto()).startsWith("¿Cuánto fue?");
        assertThat(pregunta.botones()).isEmpty();
        verify(movimientos, never()).crearPendiente(any(), any(), anyString());

        Respuesta propuesta = procesador.procesarTexto(CHAT, "3.700");

        ArgumentCaptor<MovimientoInterpretado> creado = ArgumentCaptor.forClass(MovimientoInterpretado.class);
        verify(movimientos).crearPendiente(creado.capture(), eq(Origen.TEXTO), anyString());
        assertThat(creado.getValue().monto()).isEqualByComparingTo("3700");
        assertThat(creado.getValue().descripcion()).isEqualTo("Pan de pancho y papas");
        assertThat(propuesta.botones()).hasSize(3);
        verify(usoIa).asociarMovimiento(8L, 15L);
    }

    @Test
    void siDespuesDePreguntarElMontoMandaOtraCosaSeInterpretaComoNuevo() {
        when(interpretador.interpretar("pan y papas"))
                .thenReturn(new InterpretadorService.FaltaMonto(panYPapas.conMonto(null), 8L));
        when(interpretador.interpretar("kiosco 1500")).thenReturn(new InterpretadorService.Interpretado(panYPapas, null));

        procesador.procesarTexto(CHAT, "pan y papas");
        procesador.procesarTexto(CHAT, "kiosco 1500");

        verify(interpretador).interpretar("kiosco 1500");
        // Y ya no queda esperando: un número suelto se interpreta normalmente.
        when(interpretador.interpretar("500")).thenReturn(new InterpretadorService.NoEsGasto());
        procesador.procesarTexto(CHAT, "500");
        verify(interpretador).interpretar("500");
    }

    @Test
    void siLaIaNoEstaDisponibleSugiereElFormatoSimple() {
        when(interpretador.interpretar(anyString())).thenThrow(new IaNoDisponibleException("No pude consultar a la IA", null));

        Respuesta r = procesador.procesarTexto(CHAT, "algo largo");

        assertThat(r.texto()).contains("No pude consultar a la IA").contains("kiosco 1500");
        verifyNoInteractions(movimientos);
    }

    @Test
    void mensajeQueNoEsGasto() {
        when(interpretador.interpretar("hola")).thenReturn(new InterpretadorService.NoEsGasto());

        assertThat(procesador.procesarTexto(CHAT, "hola").texto()).contains("No encontré un gasto");
    }

    @Test
    void comandos() {
        assertThat(procesador.procesarTexto(CHAT, "/start").texto()).isEqualTo(ProcesadorMensajes.BIENVENIDA);
        assertThat(procesador.procesarTexto(CHAT, "/ayuda").texto()).isEqualTo(ProcesadorMensajes.AYUDA);
        assertThat(procesador.procesarTexto(CHAT, "/AYUDA@gastito_bot").texto()).isEqualTo(ProcesadorMensajes.AYUDA);
        assertThat(procesador.procesarTexto(CHAT, "/cualquiera").texto()).contains("No conozco ese comando");
        verifyNoInteractions(interpretador);
    }

    @Test
    void comandoHoy() {
        when(movimientos.hoy()).thenReturn(new MovimientoService.ResumenDia(List.of(), BigDecimal.ZERO));
        assertThat(procesador.procesarTexto(CHAT, "/hoy").texto()).isEqualTo("Hoy no registraste gastos.");

        Movimiento m = Movimiento.pendiente(panYPapas, Origen.TEXTO, "x", AHORA);
        when(movimientos.hoy()).thenReturn(new MovimientoService.ResumenDia(List.of(m, m), new BigDecimal("7400")));
        assertThat(procesador.procesarTexto(CHAT, "/hoy").texto()).isEqualTo("""
                Gastos de hoy:
                • Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42
                • Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42

                Total: $7.400""");
    }

    @Test
    void comandoMes() {
        when(movimientos.mesActual()).thenReturn(new MovimientoService.ResumenMes(YearMonth.of(2026, 10), List.of(
                new TotalPorCategoria("Supermercado", new BigDecimal("30000")),
                new TotalPorCategoria("Kiosco", new BigDecimal("5200.50"))), new BigDecimal("35200.50")));

        assertThat(procesador.procesarTexto(CHAT, "/mes").texto()).isEqualTo("""
                Octubre 2026:
                • Supermercado: $30.000
                • Kiosco: $5.200,50

                Total: $35.200,50""");
    }

    @Test
    void comandoCostos() {
        when(usoIa.resumenMesActual()).thenReturn(new ResumenUsoIa(3, 6000, 250, new BigDecimal("0.00072500")));

        assertThat(procesador.procesarTexto(CHAT, "/costos").texto()).isEqualTo("""
                Uso de la IA en octubre 2026:
                • Llamadas: 3
                • Tokens: 6000 de entrada, 250 de salida
                • Costo: US$ 0,000725""");
    }

    @Test
    void guardarConfirmaYMuestraElTotalDelMesEnLaCategoria() {
        Movimiento m = Movimiento.pendiente(panYPapas, Origen.TEXTO, "x", AHORA);
        m.confirmar();
        when(movimientos.confirmar(15L)).thenReturn(new MovimientoService.Confirmacion(m, new BigDecimal("5200")));

        ProcesadorMensajes.ResultadoBoton r = procesador.procesarBoton("g:15");

        assertThat(r.textoMensaje()).isEqualTo("""
                ✅ Guardado
                Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42
                En octubre 2026 llevás $5.200 en Kiosco.""");
        assertThat(r.aviso()).isEqualTo("Guardado");
    }

    @Test
    void cancelarYEditarDescartanElPendiente() {
        Movimiento m = Movimiento.pendiente(panYPapas, Origen.TEXTO, "x", AHORA);
        when(movimientos.cancelar(15L)).thenReturn(m);

        assertThat(procesador.procesarBoton("c:15").textoMensaje()).startsWith("❌ Cancelado\nKiosco – $3.700");
        assertThat(procesador.procesarBoton("e:15").textoMensaje())
                .startsWith("✏️ Descartado para corregir")
                .endsWith("Mandame el gasto de nuevo con los cambios.");
    }

    @Test
    void tocarDosVecesNoHaceNadaRaro() {
        Movimiento m = Movimiento.pendiente(panYPapas, Origen.TEXTO, "x", AHORA);
        m.confirmar();
        when(movimientos.cancelar(15L)).thenThrow(new MovimientoNoPendienteException(m));

        assertThat(procesador.procesarBoton("c:15").textoMensaje()).startsWith("✅ Ya estaba guardado");
    }

    @Test
    void botonesInvalidos() {
        when(movimientos.confirmar(99L)).thenThrow(new NoSuchElementException());

        assertThat(procesador.procesarBoton("g:99").aviso()).isEqualTo("No encontré ese gasto");
        assertThat(procesador.procesarBoton("x:15").aviso()).isEqualTo("Botón inválido");
        assertThat(procesador.procesarBoton("g:abc").aviso()).isEqualTo("Botón inválido");
        assertThat(procesador.procesarBoton(null).aviso()).isEqualTo("Botón inválido");
    }
}
