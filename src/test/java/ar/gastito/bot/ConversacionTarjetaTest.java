package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.tarjeta.CalendarioTarjeta;
import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.EstadoResumenTarjeta;
import ar.gastito.tarjeta.VistaResumenTarjeta;

class ConversacionTarjetaTest {

    static final long CHAT = 42;
    static final int MENSAJE = 100;
    static final long CERRADO = 7;
    static final long PROXIMO = 8;
    static final long GOLD = 1;
    static final long MP = 2;

    static final Cuenta GALICIA = conId(new Cuenta("Galicia", "galicia"), 1L);
    static final Cuenta CUENTA_MP = conId(new Cuenta("Mercado Pago", "mercadopago,mp"), 2L);
    static final TarjetaCredito MASTERCARD = conId(new TarjetaCredito("MasterCard Gold", "Galicia", GALICIA, null,
            "M", "master,mastercard,gold,la de galicia,galicia"), GOLD);
    static final TarjetaCredito TARJETA_MP = conId(new TarjetaCredito("Mercado Pago", "Mercado Pago", CUENTA_MP, null,
            "P", "la de mercado pago,tarjeta de mercado pago,mercado pago,mp"), MP);

    private final CicloTarjetaService ciclo = mock(CicloTarjetaService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final ConversacionTarjeta conversacion = new ConversacionTarjeta(ciclo, catalogos,
            new FormatoMensajes(RELOJ));

    @BeforeEach
    void conUnaSolaTarjeta() {
        conTarjetas(MASTERCARD);
    }

    private void conTarjetas(TarjetaCredito... tarjetas) {
        when(catalogos.cargar()).thenReturn(new Catalogo(List.of(), List.of(), List.of(GALICIA, CUENTA_MP),
                List.of(tarjetas)));
    }

    /** El que cerró el 29/10 y vence el 11/11, con $200.000 cargados. */
    static VistaResumenTarjeta cerrado(String totalReal) {
        return cerrado(totalReal, "MasterCard Gold", "Galicia", CERRADO, GOLD);
    }

    static VistaResumenTarjeta cerrado(String totalReal, String tarjeta, String cuenta, long id, long tarjetaId) {
        BigDecimal real = totalReal == null ? null : new BigDecimal(totalReal);
        return new VistaResumenTarjeta(id, YearMonth.of(2026, 10), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11), false, EstadoResumenTarjeta.CERRADO,
                new BigDecimal("180000"), new BigDecimal("20000"), new BigDecimal("200000"), real,
                real == null ? null : real.subtract(new BigDecimal("200000")), tarjeta, cuenta, List.of(), tarjetaId);
    }

    /** El próximo, estimado: cierra ≈ 30/11 y vence ≈ 14/12. */
    static VistaResumenTarjeta proximo() {
        return new VistaResumenTarjeta(PROXIMO, YearMonth.of(2026, 11), LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 14), true, EstadoResumenTarjeta.ABIERTO,
                new BigDecimal("2500"), new BigDecimal("10000"), new BigDecimal("12500"), null, null,
                "MasterCard Gold", "Galicia", List.of(), GOLD);
    }

    static CicloTarjetaService.Panel panelGold() {
        return new CicloTarjetaService.Panel("MasterCard Gold", "Galicia", List.of(cerrado(null)), proximo(),
                List.of(), BigDecimal.ZERO, List.of(), GOLD, null, "M");
    }

    /** La de Mercado Pago, todavía sin ningún cierre. */
    static CicloTarjetaService.Panel panelMpSinFechas() {
        return new CicloTarjetaService.Panel("Mercado Pago", "Mercado Pago", List.of(), null, List.of(),
                BigDecimal.ZERO, List.of(), MP, null, "P");
    }

    private void conPaneles(CicloTarjetaService.Panel... paneles) {
        List<CicloTarjetaService.Panel> lista = List.of(paneles);
        when(ciclo.paneles()).thenReturn(new CicloTarjetaService.Paneles(lista,
                lista.stream().filter(CicloTarjetaService.Panel::configurado).map(p -> p.proximo().cargado())
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lista.stream().map(CicloTarjetaService.Panel::totalPorPagar).reduce(BigDecimal.ZERO,
                        BigDecimal::add)));
        Arrays.stream(paneles).forEach(p -> when(ciclo.panel(p.tarjetaId())).thenReturn(Optional.of(p)));
    }

    private void conPanel() {
        conPaneles(panelGold());
    }

    /** El texto del último mensaje enviado o editado. */
    private static String texto(List<Accion> acciones) {
        return acciones.reversed().stream().<String>mapMulti((a, salida) -> {
            if (a instanceof Accion.Enviar e) {
                salida.accept(e.html());
            } else if (a instanceof Accion.Editar e) {
                salida.accept(e.html());
            }
        }).findFirst().orElseThrow(() -> new AssertionError("No hay ningún mensaje: " + acciones));
    }

    private static List<String> datos(Accion accion) {
        return ((Accion.Enviar) accion).teclado().botones().stream().map(Teclado.Boton::datos).toList();
    }

    // --- Cómo viene ---

    @Test
    void sinCicloPideElUltimoCierre() {
        conPaneles(new CicloTarjetaService.Panel("MasterCard Gold", "Galicia", List.of(), null, List.of(),
                BigDecimal.ZERO, List.of(), GOLD, null, "M"));

        assertThat(texto(conversacion.estado())).contains("Todavía no sé cuándo cierra la tarjeta");
    }

    @Test
    void elEstadoMuestraElCerradoPorPagarYElProximoConFechasEstimadas() {
        conPanel();

        String texto = texto(conversacion.estado());

        assertThat(texto).contains("Vence el 11/11 · según lo cargado <b>$200.000</b>");
        assertThat(texto).contains("Cierra ≈ 30/11 · vence ≈ 14/12");
        assertThat(texto).contains("Llevás <b>$12.500</b> (compras $2.500 + cuotas $10.000)");
        assertThat(texto).contains("≈ es una estimación");
    }

    @Test
    void conDosTarjetasMuestraUnBloquePorTarjetaYLaQueNoTieneFechasLasPide() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        conPaneles(panelGold(), panelMpSinFechas());

        String texto = texto(conversacion.estado());

        assertThat(texto).contains("💳 <b>MasterCard Gold</b>").contains("Llevás <b>$12.500</b>");
        assertThat(texto).contains("💳 <b>Mercado Pago</b>\nTodavía no sé cuándo cierra");
        assertThat(texto).doesNotContain("Entre todas");
    }

    @Test
    void conDosTarjetasConCicloSumaElTotalEntreTodas() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        VistaResumenTarjeta proximoMp = new VistaResumenTarjeta(20L, YearMonth.of(2026, 11),
                LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 5), false,
                EstadoResumenTarjeta.ABIERTO, new BigDecimal("7500"), BigDecimal.ZERO, new BigDecimal("7500"), null,
                null, "Mercado Pago", "Mercado Pago", List.of(), MP);
        conPaneles(panelGold(), new CicloTarjetaService.Panel("Mercado Pago", "Mercado Pago", List.of(), proximoMp,
                List.of(), BigDecimal.ZERO, List.of(), MP, null, "P"));

        assertThat(texto(conversacion.estado())).contains(
                "➕ <b>Entre todas</b>: llevás $20.000 en los próximos resúmenes y hay $200.000 por pagar.");
    }

    @Test
    void cuantoLlevoDeLaMasterRespondeSoloEsa() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        conPaneles(panelGold(), panelMpSinFechas());

        String texto = texto(conversacion.estado("¿cuánto llevo de la master?"));

        assertThat(texto).contains("MasterCard Gold").doesNotContain("Mercado Pago");
    }

    // --- Fechas ---

    @Test
    void conLasDosFechasLasGuarda() {
        VistaResumenTarjeta confirmado = new VistaResumenTarjeta(PROXIMO, YearMonth.of(2026, 11),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11), false,
                EstadoResumenTarjeta.ABIERTO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                "MasterCard Gold", "Galicia", List.of(), GOLD);
        when(ciclo.cargarFechas(GOLD, LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11))).thenReturn(
                new CicloTarjetaService.FechasCargadas(confirmado, new CalendarioTarjeta.Fechas(
                        YearMonth.of(2026, 11), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 14), false)));

        String texto = texto(conversacion.fechas(CHAT, new ValidadorInterpretacion.FechasTarjeta(
                LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11)), ""));

        assertThat(texto).contains("cierra el 26/11 y vence el 11/12");
        assertThat(texto).contains("Lo tenía estimado para el ≈ 30/11");
    }

    @Test
    void siFaltaElVencimientoLoPreguntaYDespuesGuarda() {
        when(ciclo.cargarFechas(anyLong(), any(), any()))
                .thenReturn(new CicloTarjetaService.FechasCargadas(proximo(), null));

        String pregunta = texto(conversacion.fechas(CHAT,
                new ValidadorInterpretacion.FechasTarjeta(LocalDate.of(2026, 11, 26), null), ""));
        assertThat(pregunta).contains("¿Y cuándo vence?");
        assertThat(conversacion.esperando(CHAT)).isTrue();

        assertThat(conversacion.texto(CHAT, "el 11")).isPresent();
        verify(ciclo).cargarFechas(GOLD, LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));
        assertThat(conversacion.esperando(CHAT)).isFalse();
    }

    @Test
    void siLoQueEscribeNoEsUnaFechaSeProcesaNormal() {
        conversacion.fechas(CHAT, new ValidadorInterpretacion.FechasTarjeta(LocalDate.of(2026, 11, 26), null), "");

        assertThat(conversacion.texto(CHAT, "kiosco 1500")).isEmpty();
        verify(ciclo, never()).cargarFechas(anyLong(), any(), any());
    }

    @Test
    void lasFechasDeLaTarjetaDeMercadoPagoVanAEsaTarjeta() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        when(ciclo.cargarFechas(anyLong(), any(), any()))
                .thenReturn(new CicloTarjetaService.FechasCargadas(proximo(), null));

        conversacion.fechas(CHAT, new ValidadorInterpretacion.FechasTarjeta(LocalDate.of(2026, 11, 24),
                LocalDate.of(2026, 12, 5)), "la tarjeta de mercado pago cierra el 24/11 y vence el 5/12", "");

        verify(ciclo).cargarFechas(MP, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 5));
    }

    @Test
    void conDosTarjetasYSinDecirCualPreguntaConUnBotonPorTarjeta() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        when(ciclo.cargarFechas(anyLong(), any(), any()))
                .thenReturn(new CicloTarjetaService.FechasCargadas(proximo(), null));

        List<Accion> pregunta = conversacion.fechas(CHAT, new ValidadorInterpretacion.FechasTarjeta(
                LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 5)), "la tarjeta cierra el 24/11 y vence el 5/12",
                "");

        assertThat(texto(pregunta)).contains("¿De qué tarjeta son esas fechas?");
        assertThat(datos(pregunta.getFirst())).containsExactly("tq:1", "tq:2");
        verify(ciclo, never()).cargarFechas(anyLong(), any(), any());

        List<Accion> respuesta = conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.ELEGIR_TARJETA, MP, null);

        verify(ciclo).cargarFechas(MP, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 5));
        assertThat(texto(respuesta)).contains("Listo");
    }

    @Test
    void elRecordatorioDeFechasConTodaviaNoVuelveEnTresDias() {
        Accion.Enviar pregunta = conversacion.preguntaDeFechas(new CicloTarjetaService.PreguntaFechas(GOLD,
                "MasterCard Gold", proximo()));
        assertThat(pregunta.html()).contains("¿Ya sabés el próximo cierre y vencimiento de la tarjeta?");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("tf:8");
        when(ciclo.posponerFechas(PROXIMO)).thenReturn(LocalDate.of(2026, 11, 3));

        String respuesta = texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.TODAVIA_NO_FECHAS, PROXIMO,
                null));

        assertThat(respuesta).contains("Te vuelvo a preguntar el 03/11");
    }

    @Test
    void unaTarjetaSinNingunCierrePideLasFechasYTodaviaNoLaPospone() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        Accion.Enviar pregunta = conversacion.preguntaDeFechas(new CicloTarjetaService.PreguntaFechas(MP,
                "Mercado Pago", null));

        assertThat(pregunta.html()).contains("¿Cuándo cierra y vence la <b>Mercado Pago</b>?");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("tf:-2");
        when(ciclo.posponerFechas(-MP)).thenReturn(LocalDate.of(2026, 11, 3));

        assertThat(texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.TODAVIA_NO_FECHAS, -MP, null)))
                .contains("Te vuelvo a preguntar el 03/11");
    }

    @Test
    void conDosTarjetasLosAvisosDicenDeCual() {
        conTarjetas(MASTERCARD, TARJETA_MP);

        assertThat(conversacion.avisoDeVencimiento(cerrado(null, "Mercado Pago", "Mercado Pago", 30, MP)).html())
                .startsWith("💳 El 11/11 vence la <b>Mercado Pago</b>: ").endsWith("Se debita de Mercado Pago.");
        assertThat(conversacion.preguntaDeDebito(cerrado(null)).html())
                .contains("¿Se debitó el resumen de la <b>MasterCard Gold</b>");
    }

    // --- Vencimiento, total y débito ---

    @Test
    void elAvisoDelVencimientoCargaElTotalDelResumen() {
        Accion.Enviar aviso = conversacion.avisoDeVencimiento(cerrado(null));
        assertThat(aviso.html()).isEqualTo(
                "💳 El 11/11 vence la tarjeta: según lo cargado, <b>$200.000</b>. Se debita de Galicia.");
        assertThat(aviso.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🧾 Cargar total del resumen");
        when(ciclo.cargarTotal(null, CERRADO, new BigDecimal("250000"))).thenReturn(
                new CicloTarjetaService.TotalCargado(cerrado("250000"), new BigDecimal("250000"),
                        new BigDecimal("200000"), new BigDecimal("50000")));

        conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.CARGAR_TOTAL, CERRADO, null);
        String respuesta = texto(conversacion.texto(CHAT, "250.000").orElseThrow());

        assertThat(respuesta).contains("El resumen dice $250.000 y cargaste $200.000: faltan <b>$50.000</b> sin cargar.");
    }

    @Test
    void unTotalQueCoincideLoDice() {
        conPanel();
        when(ciclo.cargarTotal(GOLD, null, new BigDecimal("200000"))).thenReturn(new CicloTarjetaService.TotalCargado(
                cerrado("200000"), new BigDecimal("200000"), new BigDecimal("200000"), BigDecimal.ZERO));

        assertThat(texto(conversacion.total(CHAT, new BigDecimal("200000"), null, "el resumen es de 200.000", "")))
                .contains("Coincide");
    }

    @Test
    void elTotalSinDecirLaTarjetaVaALaUnicaConUnResumenCerrado() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        conPaneles(panelGold(), panelMpSinFechas());
        when(ciclo.cargarTotal(GOLD, null, new BigDecimal("250000"))).thenReturn(new CicloTarjetaService.TotalCargado(
                cerrado("250000"), new BigDecimal("250000"), new BigDecimal("200000"), new BigDecimal("50000")));

        assertThat(texto(conversacion.total(CHAT, new BigDecimal("250000"), null, "el resumen es de 250.000", "")))
                .contains("Resumen de la MasterCard Gold que vence el 11/11");
    }

    @Test
    void elDiaDelVencimientoConSiRegistraElPago() {
        Accion.Enviar pregunta = conversacion.preguntaDeDebito(cerrado("250000"));
        assertThat(pregunta.html()).contains("¿Se debitó el resumen de la tarjeta (<b>$250.000</b>)?");
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::datos).containsExactly("ts:7", "tn:7");
        when(ciclo.pagar(null, CERRADO, null, Origen.AUTOMATICO, null)).thenReturn(new CicloTarjetaService.Pago(
                Movimiento.pagoTarjeta(new BigDecimal("250000"), "Resumen", null, null, DatosDePrueba.AHORA,
                        Origen.AUTOMATICO, null, DatosDePrueba.AHORA), cerrado("250000")));

        String respuesta = texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.DEBITADO, CERRADO, null));

        assertThat(respuesta).contains("Débito registrado</b>: $250.000 desde Galicia");
        assertThat(respuesta).contains("No cuenta como gasto");
    }

    @Test
    void todaviaNoSeDebitoNoRegistraNada() {
        texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.TODAVIA_NO_DEBITO, CERRADO, null));

        verify(ciclo, never()).pagar(any(), any(), any(), any(), any());
    }

    @Test
    void elDebitoDichoEnUnMensajeSeConfirmaConUnBoton() {
        conPanel();

        Accion.Enviar tarjeta = (Accion.Enviar) conversacion.pago(CHAT, new BigDecimal("250000"),
                "se debitó la tarjeta, 250.000", "").get(0);

        assertThat(tarjeta.html()).contains("¿Registro el débito del resumen que vence el 11/11?");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("tc:7:25000000", "tx:7");
        verify(ciclo, never()).pagar(any(), any(), any(), any(), any());

        when(ciclo.pagar(isNull(), eq(CERRADO), eq(new BigDecimal("250000.00")), eq(Origen.TEXTO), isNull()))
                .thenReturn(new CicloTarjetaService.Pago(Movimiento.pagoTarjeta(new BigDecimal("250000"), "Resumen",
                        null, null, DatosDePrueba.AHORA, Origen.TEXTO, null, DatosDePrueba.AHORA), cerrado("250000")));
        assertThat(texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.CONFIRMAR_PAGO, CERRADO, 25000000L)))
                .contains("Débito registrado");
    }

    @Test
    void elPagoDeLaTarjetaDeMercadoPagoSinResumenSeRegistraDesdeSuCuenta() {
        conTarjetas(MASTERCARD, TARJETA_MP);
        conPaneles(panelGold(), panelMpSinFechas());

        Accion.Enviar propuesta = (Accion.Enviar) conversacion.pago(CHAT, new BigDecimal("85000"),
                "pagué la tarjeta de mercado pago, 85.000", "").getFirst();

        assertThat(propuesta.html()).contains("de la Mercado Pago. ¿Registro igual el pago?")
                .contains("<b>$85.000</b> desde Mercado Pago");
        assertThat(datos(propuesta)).containsExactly("tc:-2:8500000", "tx:-2");
        when(ciclo.pagarSinResumen(MP, new BigDecimal("85000.00"), Origen.TEXTO, null)).thenReturn(
                Movimiento.pagoTarjeta(new BigDecimal("85000"), "Pago Mercado Pago", TARJETA_MP, CUENTA_MP,
                        DatosDePrueba.AHORA, Origen.TEXTO, null, DatosDePrueba.AHORA));

        assertThat(texto(conversacion.boton(CHAT, MENSAJE, ConversacionTarjeta.CONFIRMAR_PAGO, -MP, 8500000L)))
                .contains("Pago de la Mercado Pago registrado</b>: $85.000 desde Mercado Pago.");
    }

    @Test
    void sinResumenCerradoNoHayDebitoParaRegistrar() {
        conPaneles(new CicloTarjetaService.Panel("MasterCard Gold", "Galicia", List.of(), proximo(), List.of(),
                BigDecimal.ZERO, List.of(), GOLD, null, "M"));

        assertThat(texto(conversacion.pago(CHAT, null, "se debitó la tarjeta", "")))
                .contains("No tengo ningún resumen cerrado sin debitar");
    }

    // --- Compras en dólares ---

    /** El próximo con Steam US$ 20 ≈ $30.000, iCloud US$ 5 sin estimar y una remera de $2.500. */
    static VistaResumenTarjeta proximoConDolares() {
        LocalDateTime dia = LocalDateTime.of(2026, 10, 31, 9, 0);
        return new VistaResumenTarjeta(PROXIMO, YearMonth.of(2026, 11), LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 14), true, EstadoResumenTarjeta.ABIERTO,
                new BigDecimal("32500"), BigDecimal.ZERO, new BigDecimal("32500"), null, null,
                "MasterCard Gold", "Galicia", List.of(
                        new VistaResumenTarjeta.Linea(1L, dia, "Steam", "Suscripciones", "📺",
                                new BigDecimal("30000"), null, null, false, new BigDecimal("20"), true),
                        new VistaResumenTarjeta.Linea(2L, dia, "iCloud", "Suscripciones", "📺",
                                BigDecimal.ZERO, null, null, false, new BigDecimal("5"), true),
                        new VistaResumenTarjeta.Linea(3L, dia, "Remera", "Ropa", "👕",
                                new BigDecimal("2500"), null, null, false)), GOLD);
    }

    @Test
    void elProximoResumenMuestraAparteLasComprasEnDolares() {
        conPaneles(new CicloTarjetaService.Panel("MasterCard Gold", "Galicia", List.of(), proximoConDolares(),
                List.of(), BigDecimal.ZERO, List.of(), GOLD, null, "M"));

        String texto = texto(conversacion.estado());

        assertThat(texto).contains("Llevás <b>$32.500</b>");
        assertThat(texto).contains("💵 Compras en dólares: US$ 25 (≈ $30.000 · 1 sin estimar)");
    }

    @Test
    void sinComprasEnDolaresNoLasMenciona() {
        conPanel();

        assertThat(texto(conversacion.estado())).doesNotContain("dólares");
    }

    @Test
    void laDiferenciaConElTotalRealAvisaQueLosDolaresSonEstimados() {
        VistaResumenTarjeta r = proximoConDolares();

        String texto = conversacion.textoTotal(new CicloTarjetaService.TotalCargado(r, new BigDecimal("40000"),
                r.cargado(), new BigDecimal("7500")));

        assertThat(texto).contains("faltan <b>$7.500</b> sin cargar");
        assertThat(texto).contains("incluye US$ 25 en compras en dólares con los pesos estimados (≈ $30.000)");
    }

    @Test
    void sinDolaresEstimadosLaDiferenciaNoLosMenciona() {
        VistaResumenTarjeta r = cerrado("250000");

        assertThat(conversacion.textoTotal(new CicloTarjetaService.TotalCargado(r, new BigDecimal("250000"),
                r.cargado(), new BigDecimal("50000")))).doesNotContain("dólares");
    }
}
