package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.recibo.ControlRecibo;
import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.ReciboService;
import ar.gastito.recibo.TipoRecibo;
import ar.gastito.sueldo.SueldoService;

class TarjetasReciboTest {

    private final SueldoService sueldo = mock(SueldoService.class);
    private final TarjetasRecibo tarjetas = new TarjetasRecibo(new FormatoMensajes(RELOJ), sueldo);

    private static ReciboService.Renglon renglon(String descripcion, GrupoConcepto grupo, String importe) {
        return new ReciboService.Renglon(descripcion, grupo, new BigDecimal(importe), null);
    }

    private static final ReciboService.Renglon[] DESCUENTOS = {
        renglon("Sueldo básico", GrupoConcepto.REMUNERATIVO, "1250000.00"),
        renglon("Jubilación 11%", GrupoConcepto.DESCUENTO, "137500.00"),
        renglon("Obra social 3%", GrupoConcepto.DESCUENTO, "37500.00"),
        renglon("Ley 19.032 3%", GrupoConcepto.DESCUENTO, "37500.00")};

    private static ControlRecibo.Resultado sinNadaRaro(boolean conCalculo) {
        return new ControlRecibo.Resultado(List.of(), conCalculo, true);
    }

    private static ReciboService.VistaRecibo vista(BigDecimal calculado, ReciboService.Cobro cobro,
            ControlRecibo.Resultado control, ReciboService.Renglon... conceptos) {
        return new ReciboService.VistaRecibo(7L, cobro, "Empresa Ejemplo", YearMonth.of(2026, 10),
                TipoRecibo.SUELDO_MENSUAL, LocalDate.of(2026, 11, 3), new BigDecimal("1250000.00"),
                new BigDecimal("212500.00"), new BigDecimal("1037500.00"), List.of(conceptos), null, true, calculado,
                control);
    }

    private static ReciboService.Cobro cobro(String monto) {
        return new ReciboService.Cobro(ID, new BigDecimal(monto), AHORA, false);
    }

    @Test
    void laTarjetaMuestraBrutoDescuentosConSuDetalleYNeto() {
        String texto = tarjetas.tarjeta(vista(null, cobro("1037500"), sinNadaRaro(false), DESCUENTOS), false);

        assertThat(texto).startsWith("💼 <b>Recibo · Octubre 2026 · Empresa Ejemplo</b>\n<pre>");
        assertThat(texto).contains("Bruto ", "$1.250.000", "Descuentos ", "−$212.500", "  Jubilación 11%",
                "−$137.500", "  Obra social 3%", "−$37.500", "💰 Neto ", "$1.037.500</pre>");
        assertThat(texto).doesNotContain("Sueldo básico");
    }

    @Test
    void losImportesLleganAlMargenDerecho() {
        String bloque = tarjetas.tarjeta(vista(null, cobro("1037500"), sinNadaRaro(false), DESCUENTOS), false)
                .split("<pre>")[1].split("</pre>")[0];

        assertThat(bloque.lines()).allSatisfy(linea -> assertThat(linea.length()).isLessThanOrEqualTo(34));
        assertThat(bloque.lines().filter(l -> l.startsWith("Bruto") || l.startsWith("Descuentos")
                || l.startsWith("💰")).map(String::strip)).allMatch(l -> l.matches(".* \\.{2,} .*"));
    }

    @Test
    void conMuchosDescuentosMuestraLosPrimerosYCuentaElResto() {
        ReciboService.Renglon[] muchos = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> renglon("Descuento " + i, GrupoConcepto.DESCUENTO, "1000.00"))
                .toArray(ReciboService.Renglon[]::new);

        String texto = tarjetas.tarjeta(vista(null, cobro("1037500"), sinNadaRaro(false), muchos), false);

        assertThat(texto).contains("Descuento 8").doesNotContain("Descuento 9").contains("…y 3 más");
    }

    @Test
    void unAguinaldoLoDiceEnElTitulo() {
        ReciboService.VistaRecibo base = vista(null, null, sinNadaRaro(false), DESCUENTOS);
        ReciboService.VistaRecibo aguinaldo = new ReciboService.VistaRecibo(base.id(), null, base.empleador(),
                YearMonth.of(2026, 6), TipoRecibo.AGUINALDO, null, base.bruto(), base.descuentos(), base.neto(),
                base.conceptos(), null, true, null, sinNadaRaro(false));

        assertThat(tarjetas.tarjeta(aguinaldo, false))
                .startsWith("💼 <b>Recibo · Junio 2026 · Empresa Ejemplo</b> (Aguinaldo)");
    }

    @Test
    void sinNadaRaroYConCalculoDiceQueCoincide() {
        when(sueldo.comparar(any(), any(), any())).thenReturn(new SueldoService.Comparacion(YearMonth.of(2026, 10),
                new BigDecimal("1037500"), new BigDecimal("1037500"), BigDecimal.ZERO, new BigDecimal("0.0"), false));

        String texto = tarjetas.tarjeta(vista(new BigDecimal("1037500"), cobro("1037500"), sinNadaRaro(true),
                DESCUENTOS), false);

        assertThat(texto).contains("✅ <b>El recibo coincide con tu cálculo</b>").doesNotContain("Para revisar");
    }

    @Test
    void sinCalculoDiceQueNoHayConQueCompararLosConceptos() {
        String texto = tarjetas.tarjeta(vista(null, cobro("1037500"), sinNadaRaro(false), DESCUENTOS), false);

        assertThat(texto).contains("✅ No encontré nada raro (ese mes no tenés cálculo para comparar los conceptos)");
    }

    @Test
    void lasCosasRarasSeListan() {
        ControlRecibo.Resultado raro = new ControlRecibo.Resultado(List.of(
                new ControlRecibo.Hallazgo(ControlRecibo.Tipo.PORCENTAJE_DISTINTO,
                        "Ley 19.032: te descontaron 2,5% y tu escala dice 3%."),
                new ControlRecibo.Hallazgo(ControlRecibo.Tipo.CONCEPTO_FALTA,
                        "Presentismo estaba en el recibo del mes anterior y ahora no está.")), true, true);

        String texto = tarjetas.tarjeta(vista(null, cobro("1037500"), raro, DESCUENTOS), false);

        assertThat(texto).contains("⚠️ <b>Para revisar:</b>", "• Ley 19.032: te descontaron 2,5% y tu escala dice 3%.",
                "• Presentismo estaba en el recibo del mes anterior y ahora no está.")
                .doesNotContain("coincide con tu cálculo");
    }

    @Test
    void elAvisoDeQueNoCierraSaleDelControl() {
        ControlRecibo.Resultado noCierra = new ControlRecibo.Resultado(List.of(new ControlRecibo.Hallazgo(
                ControlRecibo.Tipo.NO_CIERRA, "No cierra: los conceptos dan $1.037.000,00 y el neto impreso es "
                        + "$1.037.500,00 (diferencia $500,00).")), false, true);

        assertThat(tarjetas.tarjeta(vista(null, cobro("1037500"), noCierra, DESCUENTOS), false))
                .contains("• No cierra: los conceptos dan $1.037.000,00");
    }

    @Test
    void sinElCobroAvisaQueSeComparaCuandoLoCargue() {
        String texto = tarjetas.tarjeta(vista(null, null, new ControlRecibo.Resultado(List.of(), false, false),
                DESCUENTOS), false);

        assertThat(texto).contains("⏳ Todavía no cargaste lo que cobraste de octubre 2026");
    }

    @Test
    void conCalculoYCobroMuestraLaComparacion() {
        when(sueldo.comparar(YearMonth.of(2026, 10), new BigDecimal("1114400"), new BigDecimal("1110000")))
                .thenReturn(new SueldoService.Comparacion(YearMonth.of(2026, 10), new BigDecimal("1114400"),
                        new BigDecimal("1110000"), new BigDecimal("-4400"), new BigDecimal("-0.4"), false));

        String texto = tarjetas.tarjeta(vista(new BigDecimal("1114400"), cobro("1110000"), sinNadaRaro(true),
                DESCUENTOS), false);

        assertThat(texto).contains("🧮 Calculaste <b>$1.114.400</b> · Cobraste <b>$1.110.000</b> (−$4.400, −0,4%)")
                .doesNotContain("⚠️ Calculaste");
    }

    @Test
    void siLaDiferenciaPasaLaToleranciaSeMarca() {
        SueldoService.Comparacion fuera = new SueldoService.Comparacion(YearMonth.of(2026, 10),
                new BigDecimal("1114400"), new BigDecimal("1100000"), new BigDecimal("-14400"),
                new BigDecimal("-1.3"), true);

        assertThat(TarjetasRecibo.textoComparacion(fuera, new FormatoMensajes(RELOJ), true))
                .isEqualTo("⚠️ Calculaste <b>$1.114.400</b> · Cobraste <b>$1.100.000</b> (−$14.400, −1,3%)");
    }

    @Test
    void siReemplazoUnReciboLoDice() {
        assertThat(tarjetas.tarjeta(vista(null, cobro("1037500"), sinNadaRaro(false), DESCUENTOS), true))
                .contains("🔄 Ya tenía el recibo de este mes: lo reemplacé.");
    }

    @Test
    void elUnicoBotonEsDescartar() {
        assertThat(tarjetas.teclado(7).botones()).extracting(Teclado.Boton::datos).containsExactly("dr:7");
    }

    // --- El sueldo cobrado, comparado con el cálculo (tarjeta del ingreso) ---

    @Test
    void laTarjetaDelSueldoCobradoMuestraLaComparacionConElCalculo() {
        Tarjetas tarjetasDeMovimientos = new Tarjetas(new FormatoMensajes(RELOJ));
        tarjetasDeMovimientos.usarSueldo(m -> Optional.of("🧮 Calculaste $1.114.400"));
        Movimiento sueldoCobrado = conId(Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal("1110000"), null, null,
                null, AHORA, Origen.TEXTO, null, AHORA), ID);
        Movimiento extra = conId(Movimiento.ingreso(ClaseIngreso.EXTRA, new BigDecimal("25000"), "PC", null, null,
                AHORA, Origen.TEXTO, null, AHORA), ID);

        assertThat(tarjetasDeMovimientos.pendiente(sueldoCobrado)).contains("🧮 Calculaste $1.114.400");
        assertThat(tarjetasDeMovimientos.pendiente(extra)).doesNotContain("Calculaste");
    }

    private static Movimiento sueldoCobradoEl5DeNoviembre() {
        Movimiento m = conId(Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal("1110000"), null, null, null,
                java.time.LocalDateTime.of(2026, 11, 5, 12, 0), Origen.TEXTO, null, AHORA), ID);
        m.definirPeriodo(YearMonth.of(2026, 10));
        return m;
    }

    @Test
    void laTarjetaDelSueldoDiceElMesTrabajadoYCuandoSeCobro() {
        Tarjetas tarjetasDeMovimientos = new Tarjetas(new FormatoMensajes(RELOJ));

        assertThat(tarjetasDeMovimientos.pendiente(sueldoCobradoEl5DeNoviembre()))
                .contains("🏷 Sueldo de octubre · cobrado el 05/11");
    }

    @Test
    void elSueldoPendienteTieneElBotonParaCambiarElMes() {
        Tarjetas tarjetasDeMovimientos = new Tarjetas(new FormatoMensajes(RELOJ));

        assertThat(tarjetasDeMovimientos.principal(sueldoCobradoEl5DeNoviembre()).botones())
                .extracting(Teclado.Boton::datos).containsExactly("g:" + ID, "c:" + ID, "pp:" + ID);
    }

    @Test
    void unExtraNoTienePeriodoNiBotonDeMes() {
        Tarjetas tarjetasDeMovimientos = new Tarjetas(new FormatoMensajes(RELOJ));
        Movimiento extra = conId(Movimiento.ingreso(ClaseIngreso.EXTRA, new BigDecimal("25000"), "PC", null, null,
                AHORA, Origen.TEXTO, null, AHORA), ID);

        assertThat(tarjetasDeMovimientos.pendiente(extra)).doesNotContain("cobrado el");
        assertThat(tarjetasDeMovimientos.principal(extra).botones()).extracting(Teclado.Boton::datos)
                .containsExactly("g:" + ID, "c:" + ID);
    }
}
