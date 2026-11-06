package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SALIDAS;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static ar.gastito.bot.DatosDePrueba.confirmado;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.movimiento.Movimiento;
import ar.gastito.viaje.AhorroViaje;
import ar.gastito.viaje.PanelViaje;
import ar.gastito.viaje.ViajeService;

class ConversacionViajesTest {

    static final long VIAJE = 3;

    private final ViajeService servicio = mock(ViajeService.class);
    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final ConversacionViajes conversacion = new ConversacionViajes(servicio, formato, RELOJ);

    private static final LocalDate DESDE = LocalDate.of(2027, 1, 10);
    private static final LocalDate HASTA = LocalDate.of(2027, 1, 12);

    /** Bariloche: pagado 263.000 (los vuelos), falta el hospedaje ≈ 400.000 y las excursiones sin monto. */
    static PanelViaje bariloche(String estado, AhorroViaje ahorro, Integer personas, BigDecimal presupuesto) {
        List<PanelViaje.Pendiente> pendientes = List.of(
                new PanelViaje.Pendiente(10L, "Hospedaje", new BigDecimal("400000"), null, false, null, null),
                new PanelViaje.Pendiente(11L, "Excursiones", null, null, false, null, null),
                new PanelViaje.Pendiente(12L, "Vuelos", new BigDecimal("260000"), null, true, ID,
                        new BigDecimal("263000")));
        return new PanelViaje(VIAJE, "Bariloche", "🏔", DESDE, HASTA, 3, personas, presupuesto, null, estado,
                new BigDecimal("263000"), new BigDecimal("400000"), 1, new BigDecimal("663000"),
                presupuesto == null ? null : new BigDecimal("66.3"), new BigDecimal("25000"),
                new BigDecimal("30000"), pendientes,
                List.of(new PanelViaje.PorCategoria(1L, "Transporte", "🚗", new BigDecimal("263000"),
                        new BigDecimal("100.0"))),
                List.of(new PanelViaje.PorDia(LocalDate.of(2026, 11, 6), new BigDecimal("263000")),
                        new PanelViaje.PorDia(DESDE, BigDecimal.ZERO), new PanelViaje.PorDia(DESDE.plusDays(1),
                                BigDecimal.ZERO), new PanelViaje.PorDia(HASTA, BigDecimal.ZERO)),
                List.of(), List.of(), ahorro);
    }

    private static List<String> datos(Teclado t) {
        return t.botones().stream().map(Teclado.Boton::datos).toList();
    }

    @Test
    void elPanelDiceLoPagadoLoQueFaltaYCuantoAhorrar() {
        AhorroViaje ahorro = new AhorroViaje(new BigDecimal("400000"), BigDecimal.ZERO, new BigDecimal("400000"), 2,
                new BigDecimal("200000"));
        when(servicio.panel(VIAJE)).thenReturn(bariloche(PanelViaje.PROXIMO, ahorro, 2, null));

        Accion.Enviar panel = (Accion.Enviar) conversacion.boton(1, ConversacionViajes.VER, VIAJE).get(0);

        assertThat(panel.html()).startsWith("🏔 <b>Bariloche</b> · 10/1 al 12/1 (3 días) · 👥 2")
                .contains("💸 Pagado <b>$263.000</b> · Falta ≈ <b>$400.000</b> · Total estimado <b>$663.000</b>",
                        "1 pendiente sin monto", "• Hospedaje ≈ $400.000", "• Excursiones · <i>sin monto</i>",
                        "🤝 Te deben <b>$25.000</b> por el viaje",
                        "Para Bariloche falta pagar ≈ <b>$400.000</b> (sin contar lo que no tiene monto). Hasta el "
                                + "inicio quedan 2 cobros de sueldo: separá ≈ <b>$200.000</b> por mes.")
                .doesNotContain("• Vuelos").doesNotContain("Presupuesto");
        assertThat(datos(panel.teclado())).containsExactly("wp:10", "wp:11");
    }

    @Test
    void conReservasDiceCuantoTieneReservado() {
        AhorroViaje ahorro = new AhorroViaje(new BigDecimal("400000"), new BigDecimal("100000"),
                new BigDecimal("300000"), 2, new BigDecimal("150000"));

        assertThat(conversacion.ahorro(bariloche(PanelViaje.PROXIMO, ahorro, null, null))).contains(
                "Tenés $100.000 reservados en inversiones, así que faltan ≈ $300.000.",
                "separá ≈ <b>$150.000</b> por mes.");
    }

    @Test
    void conPresupuestoMuestraLaBarra() {
        assertThat(conversacion.panel(bariloche(PanelViaje.PROXIMO, null, null, new BigDecimal("1000000"))))
                .contains("📊 Presupuesto $1.000.000: ▓▓▓▓▓▓▓░░░ 66 %");
    }

    @Test
    void anotarLoQueFaltaTieneDeshacer() {
        ar.gastito.viaje.Viaje viaje = mock(ar.gastito.viaje.Viaje.class);
        when(viaje.getId()).thenReturn(VIAJE);
        when(viaje.getNombre()).thenReturn("Bariloche");
        when(servicio.lista()).thenReturn(List.of(viaje));
        PanelViaje panel = bariloche(PanelViaje.PROXIMO, null, null, null);
        when(servicio.panel(VIAJE)).thenReturn(panel);
        when(servicio.ultimoPendiente(VIAJE)).thenReturn(panel.pendientes().get(0));

        Accion.Enviar anotado = (Accion.Enviar) conversacion
                .intentar("para Bariloche me falta el hospedaje, ≈ 400.000", "").orElseThrow().get(0);

        verify(servicio).agregarPendiente(VIAJE, "hospedaje", new BigDecimal("400000"), null);
        assertThat(anotado.html()).startsWith("📝 Anotado para 🏔 Bariloche: <b>Hospedaje</b> ≈ $400.000");
        assertThat(datos(anotado.teclado())).containsExactly("wd:10", "wv:3");
        assertThat(conversacion.intentar("kiosco 1500", "")).isEmpty();
    }

    @Test
    void unViajeNuevoSeConfirmaAntesDeGuardarlo() {
        when(servicio.lista()).thenReturn(List.of());
        when(servicio.crear(any())).thenReturn(bariloche(PanelViaje.PROXIMO, null, 2, null));

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion
                .intentar("me voy a Bariloche del 10/1 al 12/1, somos 2", "").orElseThrow().get(0);

        assertThat(tarjeta.html()).contains("🧳 <b>Nuevo viaje</b>", "📍 Bariloche", "10/1 al 12/1 (3 días)",
                "👥 2 personas");
        String guardar = datos(tarjeta.teclado()).get(0);
        assertThat(guardar).startsWith("wg:");
        List<Accion> guardado = conversacion.boton(5, ConversacionViajes.GUARDAR,
                Long.parseLong(guardar.substring(3)));
        verify(servicio).crear(eq(new ViajeService.Datos("Bariloche", null, DESDE, HASTA, 2, null, null)));
        assertThat(((Accion.Editar) guardado.get(0)).html()).startsWith("✅ <b>Guardado</b>");
    }

    @Test
    void laTarjetaDeUnGastoDelViajeLoDiceYOfreceNoEsDelViaje() {
        Tarjetas tarjetas = new Tarjetas(formato);
        tarjetas.usarViajes(id -> id == VIAJE ? Optional.of("🏔 Bariloche") : Optional.empty());
        Movimiento m = confirmado("12000", SALIDAS);
        m.asociarViaje(VIAJE);

        assertThat(tarjetas.guardadoAutomatico(m)).startsWith("✅ <b>Guardado</b> · <b>$12.000</b>")
                .contains(" · 🏔 Bariloche");
        assertThat(datos(tarjetas.guardadoAutomatico(m, List.of()))).contains("wn:" + ID);
        Movimiento pendiente = conCategoria("12000", SALIDAS);
        pendiente.asociarViaje(VIAJE);
        assertThat(tarjetas.pendiente(pendiente)).contains("🏔 Bariloche");
        assertThat(datos(tarjetas.principal(pendiente))).contains("wn:" + ID);
    }

    @Test
    void elResumenFinalTieneTotalCategoriasDiasPersonasYLoQueFaltaCobrar() {
        String html = conversacion.resumenFinal(bariloche(PanelViaje.TERMINADO, null, 2, null)).html();

        assertThat(html).startsWith("🏁 <b>Resumen de 🏔 Bariloche</b> (10/1 al 12/1, 3 días)")
                .contains("💸 Te costó <b>$263.000</b>", "Además pusiste $30.000 por los demás",
                        "👥 Entre 2: ≈ <b>$146.500</b> por persona", "• 🚗 Transporte: $263.000 (100 %)",
                        "• Antes del viaje: $263.000", "• dom 10/1: $0",
                        "📥 Te falta cobrar <b>$25.000</b>");
    }

    @Test
    void durantElViajeHoyDiceCuantoLlevo() {
        when(servicio.enCurso()).thenReturn(Optional.of(new ViajeService.EnCurso("🏔 Bariloche",
                new BigDecimal("90000"), 3, new BigDecimal("30000"))));

        assertThat(conversacion.enCurso()).contains(
                "🏔 Bariloche: llevás <b>$90.000</b> en 3 días (≈ $30.000 por día)");
    }
}
