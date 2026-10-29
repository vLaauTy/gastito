package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.mockito.ArgumentCaptor;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.PagoFijoService;
import ar.gastito.fijo.RecordatorioFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.interpretacion.ParserFijo;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;

class ConversacionFijosTest {

    static final int MENSAJE = 100;
    static final Categoria SEGUROS = new Categoria(9L, "Seguros", "seguro,seguros", "Seguros", "🛡️");
    static final LocalDate PRIMER_COBRO = LocalDate.of(2026, 11, 4);

    private final GastoFijoService servicio = mock(GastoFijoService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final PagoFijoService pagos = mock(PagoFijoService.class);
    private final RecordatorioFijoService recordatorios = mock(RecordatorioFijoService.class);
    private final ConversacionFijos conversacion = new ConversacionFijos(servicio, new ParserFijo(), catalogos,
            movimientos, new FormatoMensajes(RELOJ), pagos, recordatorios, RELOJ);

    @BeforeEach
    void setUp() {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(SEGUROS, CatalogoDePrueba.KIOSCO,
                CatalogoDePrueba.OTROS), List.of(CatalogoDePrueba.CREDITO, CatalogoDePrueba.DEBITO), List.of(),
                Optional.empty()));
        when(servicio.primerCobro(anyInt())).thenReturn(PRIMER_COBRO);
    }

    private static List<String> datos(Teclado t) {
        return t.botones().stream().map(Teclado.Boton::datos).toList();
    }

    @Test
    void laTarjetaDeAltaMuestraTodoAntesDeGuardar() {
        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.alta("seguro 32900 tarjeta día 4");

        assertThat(tarjeta.html()).isEqualTo("""
                📌 <b>Nuevo gasto fijo</b>
                💰 <b>$32.900</b> · todos los meses, el día 4
                📝 Seguro
                🛡️ Seguros
                💳 Tarjeta de crédito
                📅 Primer cobro: miércoles 4/11""");
        assertThat(datos(tarjeta.teclado())).containsExactly("fa:1", "fz:1");
        verify(servicio, never()).alta(any());
    }

    @Test
    void alGuardarSeDaDeAlta() {
        conversacion.alta("seguro 32900 tarjeta día 4");
        when(servicio.alta(any())).thenReturn(new VistaFijo(7L, "Seguro", new BigDecimal("32900.00"),
                new BigDecimal("32900.00"), "Seguros", "🛡️", null, 4, Frecuencia.MENSUAL, false, null, true, true,
                PRIMER_COBRO));

        List<Accion> acciones = conversacion.boton(MENSAJE, ConversacionFijos.GUARDAR, 1, null);

        ArgumentCaptor<GastoFijoService.NuevoFijo> alta = ArgumentCaptor.forClass(GastoFijoService.NuevoFijo.class);
        verify(servicio).alta(alta.capture());
        assertThat(alta.getValue().nombre()).isEqualTo("Seguro");
        assertThat(alta.getValue().monto()).isEqualByComparingTo("32900");
        assertThat(alta.getValue().categoria()).isEqualTo(SEGUROS);
        assertThat(alta.getValue().diaDelMes()).isEqualTo(4);
        assertThat(alta.getValue().frecuencia()).isEqualTo(Frecuencia.MENSUAL);
        assertThat(((Accion.Editar) acciones.get(0)).html()).startsWith("✅ <b>Gasto fijo guardado</b>\n"
                + "Seguro: $32.900 por mes, el día 4.\n📅 Primer cobro: miércoles 4/11.");
        // Ya no está pendiente: otro toque no lo vuelve a dar de alta.
        assertThat(((Accion.Editar) conversacion.boton(MENSAJE, ConversacionFijos.GUARDAR, 1, null).get(0)).html())
                .startsWith("📌 Esa alta ya no está pendiente.");
    }

    @Test
    void sinCategoriaSeEligeConBotones() {
        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.alta("gimnasio 25000 débito el 5");

        assertThat(tarjeta.html()).contains("🏷 <b>¿Qué categoría?</b>");
        assertThat(datos(tarjeta.teclado())).containsExactly("fk:1:9", "fk:1:1", "fk:1:13", "fz:1");

        Accion.Editar elegida = (Accion.Editar) conversacion.boton(MENSAJE, ConversacionFijos.CATEGORIA, 1, 13L).get(0);

        assertThat(elegida.html()).contains("📦 Otros");
        assertThat(datos(elegida.teclado())).containsExactly("fa:1", "fz:1");
    }

    @Test
    void sinDiaOSinMontoPideElFormato() {
        assertThat(((Accion.Enviar) conversacion.alta("spotify 4999 tarjeta")).html())
                .startsWith("📌 ¿Qué día del mes se cobra?");
        assertThat(((Accion.Enviar) conversacion.alta("spotify")).html())
                .startsWith("📌 Para agregar un gasto fijo necesito");
    }

    @Test
    void losVariablesLoDicen() {
        assertThat(((Accion.Enviar) conversacion.alta("luz 28000 débito el 10 bimestral")).html())
                .contains("cada dos meses, el día 10")
                .contains("📈 Monto variable: cada mes te propongo el promedio de los últimos 3 cobros.");
    }

    @Test
    void cancelarNoGuardaNada() {
        conversacion.alta("seguro 32900 tarjeta día 4");

        assertThat(((Accion.Editar) conversacion.boton(MENSAJE, ConversacionFijos.CANCELAR, 1, null).get(0)).html())
                .startsWith("❌ <b>Cancelado</b>");
        verify(servicio, never()).alta(any());
    }

    @Test
    void elChecklistMuestraCadaEstadoYElTotalConBotonParaEsteMesNo() {
        GastoFijoService.ItemChecklist cargado = new GastoFijoService.ItemChecklist(7L, "Seguro", "🛡️",
                GastoFijoService.EstadoChecklist.CARGADO, new BigDecimal("32900"), LocalDate.of(2026, 10, 4),
                new BigDecimal("30000"), new BigDecimal("9.7"), LocalDate.of(2026, 10, 4), true, 70L, false, null);
        GastoFijoService.ItemChecklist falta = new GastoFijoService.ItemChecklist(8L, "Luz", "💡",
                GastoFijoService.EstadoChecklist.FALTA, null, null, new BigDecimal("28000"), null,
                LocalDate.of(2026, 10, 27), false, null, false, new BigDecimal("28000"));
        GastoFijoService.ItemChecklist esteMesNo = new GastoFijoService.ItemChecklist(9L, "Gimnasio", "🏋️",
                GastoFijoService.EstadoChecklist.ESTE_MES_NO, null, null, null, null, LocalDate.of(2026, 10, 15),
                false, null, false, null);
        when(servicio.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(cargado, falta, esteMesNo));
        when(servicio.cobrosPendientes()).thenReturn(List.of());

        Accion.Enviar lista = (Accion.Enviar) conversacion.lista().get(0);

        assertThat(lista.html()).isEqualTo("""
                📋 <b>Fijos de octubre 2026</b>
                ✅ Seguro: $32.900 (+9,7%)
                ⏳ Luz: vence el 27 (ref. $28.000)
                ➖ Gimnasio: este mes no

                Cargado: $32.900 · Falta: 1""");
        assertThat(datos(lista.teclado())).containsExactly("fe:8");

        when(servicio.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(cargado));
        List<Accion> resultado = conversacion.boton(MENSAJE, ConversacionFijos.ESTE_MES_NO_CHECKLIST, 8, null);
        verify(servicio).esteMesNoDelPeriodo(8L, YearMonth.of(2026, 10));
        assertThat(resultado).contains(new Accion.Avisar("⏭ Listo"));
    }

    // --- Cobros ---

    static final long COBRO = 15;
    static final long CHAT = 42;

    private Movimiento cobro(String monto) {
        Movimiento m = Movimiento.cobroDeFijo(MovimientoInterpretado.conCategoria(new BigDecimal(monto),
                "Seguro de la moto", SEGUROS, CatalogoDePrueba.CREDITO, null, null,
                LocalDate.of(2026, 11, 4).atTime(9, 0)), 7L, LocalDate.of(2026, 11, 1), DatosDePrueba.AHORA);
        return DatosDePrueba.conId(m, COBRO);
    }

    private VistaFijo seguro(boolean variable) {
        return new VistaFijo(7L, "Seguro de la moto", new BigDecimal("32900.00"), new BigDecimal("32900.00"),
                "Seguros", "🛡️", null, 4, Frecuencia.MENSUAL, variable, null, true, true, PRIMER_COBRO);
    }

    @Test
    void laPreguntaDelCobroConSusTresBotones() {
        when(servicio.vista(7L)).thenReturn(seguro(false));

        Accion.Enviar pregunta = conversacion.preguntaDeCobro(cobro("32900"));

        assertThat(pregunta.html()).startsWith("📌 <b>Se cobró el seguro de la moto: $32.900.</b> ¿Confirmás?\n📅 ")
                .contains("💳 Tarjeta de crédito").doesNotContain("promedio");
        assertThat(datos(pregunta.teclado())).containsExactly("fc:15", "fm:15", "fn:15");
        when(servicio.vista(7L)).thenReturn(seguro(true));
        assertThat(conversacion.preguntaDeCobro(cobro("32900")).html()).contains("Es el promedio de los últimos");
    }

    @Test
    void confirmarAvisaSiAumento() {
        Movimiento confirmado = cobro("36100");
        when(servicio.confirmar(COBRO, null)).thenReturn(new GastoFijoService.Cobro(confirmado, Optional.of(
                new GastoFijoService.Aumento("Seguro de la moto", new BigDecimal("32900"), new BigDecimal("36100"),
                        new BigDecimal("9.7")))));

        Accion.Editar editar = (Accion.Editar) conversacion.botonDeCobro(CHAT, MENSAJE,
                ConversacionFijos.CONFIRMAR_COBRO, COBRO).get(0);

        assertThat(editar.html()).isEqualTo("""
                ✅ <b>Guardado</b>
                📌 Seguro de la moto: $36.100
                📈 Seguro de la moto subió de $32.900 a $36.100 (+9,7%).""");
    }

    @Test
    void cambioElMontoPideElMontoYConfirmaConEse() {
        when(servicio.vista(7L)).thenReturn(seguro(false));
        when(movimientos.buscar(COBRO)).thenReturn(cobro("32900"));

        Accion.Editar pedido = (Accion.Editar) conversacion.botonDeCobro(CHAT, MENSAJE,
                ConversacionFijos.CAMBIO_EL_MONTO, COBRO).get(0);
        assertThat(pedido.html()).endsWith("✏️ <b>¿Cuánto se cobró?</b> Escribime el monto.");
        assertThat(conversacion.esperando(CHAT)).isTrue();

        assertThat(conversacion.texto(CHAT, "no sé")).singleElement().isInstanceOf(Accion.Avisar.class);
        when(servicio.confirmar(COBRO, new BigDecimal("36100"))).thenReturn(new GastoFijoService.Cobro(cobro("36100"),
                Optional.empty()));
        Accion.Editar listo = (Accion.Editar) conversacion.texto(CHAT, "36.100").get(0);

        assertThat(listo.mensajeId()).isEqualTo(MENSAJE);
        assertThat(listo.html()).isEqualTo("✅ <b>Guardado</b>\n📌 Seguro de la moto: $36.100");
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void esteMesNoNoRegistraElCobro() {
        Movimiento cancelado = cobro("32900");
        when(servicio.esteMesNo(COBRO)).thenReturn(cancelado);

        Accion.Editar editar = (Accion.Editar) conversacion.botonDeCobro(CHAT, MENSAJE, ConversacionFijos.ESTE_MES_NO,
                COBRO).get(0);

        assertThat(editar.html()).isEqualTo("⏭ <b>Este mes no</b>\nSeguro de la moto: no se registra el cobro de "
                + "noviembre 2026.");
    }

    @Test
    void unCobroYaResueltoLoDice() {
        when(servicio.confirmar(COBRO, null)).thenThrow(new MovimientoNoPendienteException(cobro("32900")));

        assertThat(((Accion.Editar) conversacion.botonDeCobro(CHAT, MENSAJE, ConversacionFijos.CONFIRMAR_COBRO, COBRO)
                .get(0)).html()).isEqualTo("📌 Ese cobro ya estaba resuelto.");
    }

    @Test
    void fijosVuelveAPreguntarPorLosCobrosSinResponder() {
        when(servicio.activos()).thenReturn(List.of(seguro(false)));
        when(servicio.vista(7L)).thenReturn(seguro(false));
        when(servicio.cobrosPendientes()).thenReturn(List.of(cobro("32900")));

        List<Accion> acciones = conversacion.lista();

        assertThat(acciones).hasSize(2);
        assertThat(((Accion.Enviar) acciones.get(1)).html()).startsWith("📌 <b>Se cobró el seguro de la moto");
    }

    // --- Monto estimado ---

    private Movimiento luz(String monto, long id, boolean estimado) {
        Movimiento m = Movimiento.cobroManualDeFijo(MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Luz",
                SEGUROS, null, null, null, LocalDate.of(2026, 10, 10).atTime(9, 0)), 8L, LocalDate.of(2026, 10, 1),
                ar.gastito.movimiento.Origen.TEXTO, null, DatosDePrueba.AHORA);
        if (estimado) {
            m.marcarMontoEstimado();
        }
        return DatosDePrueba.conId(m, id);
    }

    @Test
    void elRecordatorioTraeUnBotonPorFijoConElAproximado() {
        Accion.Enviar mensaje = conversacion.recordatorio(List.of(
                new RecordatorioFijoService.Vencido(8L, "Luz", 10, new BigDecimal("85000")),
                new RecordatorioFijoService.Vencido(9L, "Gas", 12, new BigDecimal("40000"))));

        assertThat(mensaje.html()).startsWith("📌 <b>Te falta cargar:</b> Luz (vence el 10), Gas (vence el 12).");
        assertThat(mensaje.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("✅ Luz ≈ $85.000", "✅ Gas ≈ $40.000");
        assertThat(datos(mensaje.teclado())).containsExactly("fp:8", "fp:9");
    }

    @Test
    void elBotonDelRecordatorioLoTildaPagadoYQuedanLosDemas() {
        when(pagos.pagado(8L, YearMonth.of(2026, 10), ar.gastito.movimiento.Origen.TEXTO))
                .thenReturn(luz("85000", 30, true));
        when(recordatorios.vencidos()).thenReturn(List.of(
                new RecordatorioFijoService.Vencido(9L, "Gas", 12, new BigDecimal("40000"))));

        List<Accion> acciones = conversacion.botonDePago(MENSAJE, ConversacionFijos.PAGADO, 8);

        verify(pagos).pagado(8L, YearMonth.of(2026, 10), ar.gastito.movimiento.Origen.TEXTO);
        Accion.Editar editado = (Accion.Editar) acciones.get(0);
        assertThat(editado.html()).contains("Gas (vence el 12)").doesNotContain("Luz");
        assertThat(datos(editado.teclado())).containsExactly("fp:9");
        assertThat(acciones).contains(new Accion.Avisar("✅ Luz ≈ $85.000"));

        when(recordatorios.vencidos()).thenReturn(List.of());
        Accion.Editar ultimo = (Accion.Editar) conversacion.botonDePago(MENSAJE, ConversacionFijos.PAGADO, 9).get(0);
        assertThat(ultimo.html()).startsWith("✅ <b>Listo:</b>");
    }

    @Test
    void unGastoDeUnFijoEstimadoProponeActualizarElMonto() {
        Movimiento nuevo = luz("87500", 31, false);
        Movimiento estimado = luz("85000", 30, true);
        when(pagos.estimadoDelPeriodo(8L, YearMonth.of(2026, 10))).thenReturn(Optional.of(estimado));

        Accion.Enviar propuesta = conversacion.siActualizaUnEstimado(nuevo).orElseThrow();

        assertThat(propuesta.html()).contains("monto estimado (≈ $85.000)");
        assertThat(propuesta.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🔄 Actualizar el monto ($85.000 → $87.500)", "➕ Es otro pago");
        assertThat(datos(propuesta.teclado())).containsExactly("fu:31", "fo:31");

        when(pagos.actualizarEstimadoCon(31L)).thenReturn(new PagoFijoService.Actualizacion(
                luz("87500", 30, false), new BigDecimal("85000")));
        Accion.Editar actualizado = (Accion.Editar) conversacion.botonDePago(MENSAJE,
                ConversacionFijos.ACTUALIZAR_ESTIMADO, 31).get(0);
        assertThat(actualizado.html()).isEqualTo("✅ <b>Monto actualizado</b>\n📌 Luz: $85.000 → $87.500");
    }

    @Test
    void sobreUnFijoConElMontoRealQuedaElAvisoDeSiempre() {
        Movimiento nuevo = luz("87500", 31, false);
        when(pagos.estimadoDelPeriodo(8L, YearMonth.of(2026, 10))).thenReturn(Optional.empty());
        when(servicio.yaCargadoEstePeriodo(8L, YearMonth.of(2026, 10))).thenReturn(Optional.of(luz("86000", 30,
                false)));

        assertThat(conversacion.siActualizaUnEstimado(nuevo)).isEmpty();
        assertThat(conversacion.avisoYaCargado(nuevo)).isEqualTo("\n\n⚠️ Ya cargaste luz este mes ($86.000). ¿Es otro "
                + "pago?");
    }

    @Test
    void elChecklistDelBotMuestraLosEstimados() {
        GastoFijoService.ItemChecklist estimado = new GastoFijoService.ItemChecklist(8L, "Luz", "💡",
                GastoFijoService.EstadoChecklist.CARGADO, new BigDecimal("85000"), LocalDate.of(2026, 10, 10),
                new BigDecimal("80000"), new BigDecimal("6.3"), LocalDate.of(2026, 10, 10), false, 30L, true, null);
        when(servicio.checklist(YearMonth.of(2026, 10))).thenReturn(List.of(estimado));
        when(servicio.cobrosPendientes()).thenReturn(List.of());

        assertThat(((Accion.Enviar) conversacion.lista().get(0)).html()).contains("✅ Luz: ≈ $85.000 · estimado\n");
    }
}
