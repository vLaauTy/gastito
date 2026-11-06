package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SALIDAS;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.prestamo.EstadoCuota;
import ar.gastito.prestamo.EstadoPrestamo;
import ar.gastito.prestamo.PrestamoService;
import ar.gastito.prestamo.VistaPrestamo;

/** Hoy es el 13/10/2026. Martín me debe los vuelos: 6 cuotas de $5.000 desde noviembre, los días 10. */
class ConversacionPrestamosTest {

    static final long PRESTAMO = 7;

    private final PrestamoService servicio = mock(PrestamoService.class);
    private final JuntadaService juntadas = mock(JuntadaService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final Tarjetas tarjetas = new Tarjetas(formato);
    private final ConversacionPrestamos prestamos = new ConversacionPrestamos(servicio, formato, RELOJ);
    private final ConversacionJuntadas conversacion = new ConversacionJuntadas(juntadas, movimientos, tarjetas,
            formato, prestamos);

    /** Martín, con {@code cobradas} cuotas ya pagadas. */
    static VistaPrestamo martin(int cobradas, String... montos) {
        List<VistaPrestamo.Cuota> cuotas = new ArrayList<>();
        BigDecimal cobrado = BigDecimal.ZERO;
        BigDecimal restante = BigDecimal.ZERO;
        for (int i = 0; i < 6; i++) {
            BigDecimal monto = new BigDecimal(i < montos.length ? montos[i] : "5000");
            boolean ya = i < cobradas;
            cuotas.add(new VistaPrestamo.Cuota(100L + i, i + 1, LocalDate.of(2026, 11, 10).plusMonths(i), monto,
                    ya ? EstadoCuota.COBRADA : EstadoCuota.PENDIENTE));
            if (ya) {
                cobrado = cobrado.add(monto);
            } else {
                restante = restante.add(monto);
            }
        }
        return new VistaPrestamo(PRESTAMO, "Martín", "Vuelos", new BigDecimal("30000"), 6, new BigDecimal("5000"),
                YearMonth.of(2026, 11), 10, AHORA.toLocalDate(), "Galicia", ID, null,
                restante.signum() == 0 ? EstadoPrestamo.TERMINADO : EstadoPrestamo.ACTIVO, cobrado, restante,
                cobradas, cuotas);
    }

    private static List<String> datos(Teclado t) {
        return t.botones().stream().map(Teclado.Boton::datos).toList();
    }

    private static ValidadorInterpretacion.Devolucion de(String persona, String monto) {
        return new ValidadorInterpretacion.Devolucion(monto == null ? null : new BigDecimal(monto), persona, null,
                null, null, AHORA);
    }

    @BeforeEach
    void setUp() {
        tarjetas.usarPrestamos(id -> id == ID ? Optional.of(martin(0)) : Optional.empty());
    }

    @Test
    void laTarjetaDelGastoMuestraLoQuePagueMiParteYElPrestamo() {
        Movimiento vuelos = conCategoria("263000", SALIDAS);

        String html = tarjetas.pendiente(vuelos);

        assertThat(html).contains("💸 Pagaste <b>$293.000</b>", "🙋 Tu gasto <b>$263.000</b>",
                "🤝 Préstamo a <b>Martín</b> $30.000 · 6 cuotas de $5.000 desde noviembre");
        assertThat(tarjetas.conPrestamo(vuelos)).isTrue();
    }

    @Test
    void martinMePasoLaCuotaSinMontoTomaLaProximaCuota() {
        when(servicio.deudores("Martín", null)).thenReturn(List.of(martin(1)));
        when(juntadas.deudores(any(), any(), any())).thenReturn(List.of());
        Movimiento devolucion = conCategoria("5000", null);
        when(juntadas.devolucionPendiente(eq(new BigDecimal("5000")), any(), any(), any(), any(), any()))
                .thenReturn(devolucion);

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.devolucion(de("Martín", null), Origen.TEXTO, "x", "");

        assertThat(tarjeta.html()).contains("<b>$5.000</b> · Martín",
                "🤝 Martín, cuota 2 de 6 de vuelos (te debe $25.000)");
        assertThat(datos(tarjeta.teclado())).containsExactly("pc:15:7", "c:15");
    }

    @Test
    void siLaPersonaSoloTienePrestamosNoSeOfrecenJuntadasDeOtros() {
        when(servicio.deudores("Martín", new BigDecimal("5000"))).thenReturn(List.of(martin(0)));
        when(juntadas.deudores(any(), any(), any())).thenReturn(List.of(new JuntadaService.Deudor(3L, "Pablo",
                new BigDecimal("5000"), null)));
        when(juntadas.devolucionPendiente(any(), any(), any(), any(), any(), any()))
                .thenReturn(conCategoria("5000", null));

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.devolucion(de("Martín", "5000"), Origen.TEXTO, "x", "");

        assertThat(datos(tarjeta.teclado())).containsExactly("pc:15:7", "c:15");
    }

    @Test
    void cobrarDeMenosDiceQueLasSiguientesSeAcomodaron() {
        Movimiento devolucion = conCategoria("3000", null);
        devolucion.cambiarDescripcion("Martín, cuota 1 de 6 (Vuelos)");
        when(servicio.cobrar(ID, PRESTAMO)).thenReturn(new PrestamoService.Cobro(devolucion,
                martin(1, "3000", "5400", "5400", "5400", "5400", "5400"), 1, BigDecimal.ZERO,
                new BigDecimal("5000")));

        List<Accion> acciones = conversacion.botonDePrestamo(100, ConversacionPrestamos.COBRAR, ID, PRESTAMO);

        Accion.Editar editada = (Accion.Editar) acciones.get(0);
        assertThat(editada.html()).contains("✅ <b>Cobrado</b> · $3.000",
                "Martín: 1 de 6 cuotas, faltan <b>$27.000</b>",
                "Te pasó $3.000 y eran $5.000: las cuotas que siguen se acomodaron (la próxima, $5.400 el 10/12)",
                "no cuenta como ingreso");
    }

    @Test
    void quienMeDebeIncluyeLosPrestamosConLasJuntadas() {
        when(juntadas.conDeudasPendientes()).thenReturn(List.of());
        when(servicio.conDeuda()).thenReturn(List.of(martin(1)));

        List<Accion> acciones = conversacion.abiertas();

        assertThat(((Accion.Enviar) acciones.get(0)).html()).isEqualTo("👥 Te deben <b>$25.000</b> en 1 préstamo:");
        Accion.Enviar prestamo = (Accion.Enviar) acciones.get(1);
        assertThat(prestamo.html()).contains("Martín: 1 de 6 cuotas, faltan <b>$25.000</b>", "1. 10/11 · $5.000 ✅",
                "2. 10/12 · $5.000 ⏳");
        assertThat(datos(prestamo.teclado())).containsExactly("pq:7", "pm:7");
    }

    @Test
    void elMensajeParaLaPersonaDiceLaCuotaYLaCuenta() {
        when(servicio.vista(PRESTAMO)).thenReturn(martin(1));

        String html = ((Accion.Enviar) prestamos.boton(1, ConversacionPrestamos.MENSAJE, PRESTAMO, null).get(0))
                .html();

        assertThat(html).contains("Te recuerdo la cuota 2 de 6: $5.000 (para el 10/12). En total faltan $25.000.",
                "Me la podés pasar a Galicia.");
    }

    @Test
    void elAvisoDeUnaCuotaAtrasadaOfreceArmarMensaje() {
        VistaPrestamo v = martin(0);
        Accion.Enviar aviso = prestamos.aviso(new PrestamoService.Atrasada(v, v.cuotas().get(0)));

        assertThat(aviso.html()).startsWith("⏰ <b>Martín no te pasó la cuota 1 de 6</b>\n$5.000, que era para el 10/11");
        assertThat(datos(aviso.teclado())).containsExactly("pq:7", "pm:7");
    }
}
