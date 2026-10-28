package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.AHORA;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.conId;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.ReciboService;
import ar.gastito.recibo.TipoRecibo;

class TarjetasReciboTest {

    private final Tarjetas tarjetas = new Tarjetas(new FormatoMensajes(RELOJ));
    private Movimiento m;

    private static ReciboService.Renglon renglon(String descripcion, GrupoConcepto grupo, String importe) {
        return new ReciboService.Renglon(descripcion, grupo, new BigDecimal(importe));
    }

    private static ReciboService.VistaRecibo vista(String aviso, ReciboService.Renglon... conceptos) {
        return new ReciboService.VistaRecibo(1L, ID, "Empresa Ejemplo", YearMonth.of(2026, 10), TipoRecibo.SUELDO_MENSUAL,
                LocalDate.of(2026, 11, 3), new BigDecimal("1250000.00"), new BigDecimal("212500.00"),
                new BigDecimal("1037500.00"), List.of(conceptos), aviso, true);
    }

    private static final ReciboService.Renglon[] DESCUENTOS = {
        renglon("Sueldo básico", GrupoConcepto.REMUNERATIVO, "1250000.00"),
        renglon("Jubilación 11%", GrupoConcepto.DESCUENTO, "137500.00"),
        renglon("Obra social 3%", GrupoConcepto.DESCUENTO, "37500.00"),
        renglon("Ley 19.032 3%", GrupoConcepto.DESCUENTO, "37500.00")};

    @BeforeEach
    void setUp() {
        m = conId(Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal("1037500"), "Sueldo octubre 2026", null, null,
                AHORA, Origen.RECIBO, null, AHORA), ID);
    }

    @Test
    void laTarjetaMuestraBrutoDescuentosConSuDetalleYNeto() {
        tarjetas.usarRecibos(id -> Optional.of(vista(null, DESCUENTOS)));

        String texto = tarjetas.pendiente(m);

        assertThat(texto).startsWith("💼 <b>Recibo · Octubre 2026 · Empresa Ejemplo</b>\n<pre>");
        assertThat(texto).contains("Bruto ", "$1.250.000", "Descuentos ", "−$212.500", "  Jubilación 11%",
                "−$137.500", "  Obra social 3%", "−$37.500", "💰 Neto ", "$1.037.500</pre>");
        assertThat(texto).doesNotContain("Sueldo básico").doesNotContain("⚠️");
    }

    @Test
    void losImportesLlegianAlMargenDerecho() {
        tarjetas.usarRecibos(id -> Optional.of(vista(null, DESCUENTOS)));

        String bloque = tarjetas.pendiente(m).split("<pre>")[1].split("</pre>")[0];

        assertThat(bloque.lines()).allSatisfy(linea -> assertThat(linea.length()).isLessThanOrEqualTo(34));
        assertThat(bloque.lines().filter(l -> l.startsWith("Bruto") || l.startsWith("Descuentos")
                || l.startsWith("💰")).map(String::strip)).allMatch(l -> l.matches(".* \\.{2,} .*"));
    }

    @Test
    void siNoCierraLaTarjetaAvisaQueNoCierra() {
        tarjetas.usarRecibos(id -> Optional.of(vista("No cierra: los conceptos dan $1.037.000,00 y el neto "
                + "impreso es $1.037.500,00 (diferencia $500,00).", DESCUENTOS)));

        assertThat(tarjetas.pendiente(m)).contains("⚠️ No cierra: los conceptos dan $1.037.000,00");
    }

    @Test
    void siSeEditoElMontoSeAclaraQueElReciboDeciaOtraCosa() {
        tarjetas.usarRecibos(id -> Optional.of(vista(null, DESCUENTOS)));
        m.cambiarMonto(new BigDecimal("1030000"));

        String texto = tarjetas.pendiente(m);

        assertThat(texto).contains("💰 Neto ", "$1.030.000").contains("✏️ El recibo dice $1.037.500");
    }

    @Test
    void conMuchosDescuentosMuestraLosPrimerosYCuentaElResto() {
        ReciboService.Renglon[] muchos = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> renglon("Descuento " + i, GrupoConcepto.DESCUENTO, "1000.00"))
                .toArray(ReciboService.Renglon[]::new);
        tarjetas.usarRecibos(id -> Optional.of(vista(null, muchos)));

        String texto = tarjetas.pendiente(m);

        assertThat(texto).contains("Descuento 8").doesNotContain("Descuento 9").contains("…y 3 más");
    }

    @Test
    void unAguinaldoLoDiceEnElTitulo() {
        ReciboService.VistaRecibo base = vista(null, DESCUENTOS);
        tarjetas.usarRecibos(id -> Optional.of(new ReciboService.VistaRecibo(base.id(), base.movimientoId(),
                base.empleador(), YearMonth.of(2026, 6), TipoRecibo.AGUINALDO, null, base.bruto(), base.descuentos(),
                base.neto(), base.conceptos(), null, true)));

        assertThat(tarjetas.pendiente(m)).startsWith("💼 <b>Recibo · Junio 2026 · Empresa Ejemplo</b> (Aguinaldo)");
    }

    @Test
    void guardadoMuestraElTituloYElMismoDetalle() {
        tarjetas.usarRecibos(id -> Optional.of(vista(null, DESCUENTOS)));

        assertThat(tarjetas.guardado(m, new BigDecimal("1037500"))).startsWith("✅ <b>Guardado</b>\n💼 <b>Recibo");
    }

    @Test
    void sinElDetalleDelReciboSeMuestraComoUnIngresoCualquiera() {
        // Un recibo cancelado ya no tiene detalle guardado.
        tarjetas.usarRecibos(id -> Optional.empty());

        assertThat(tarjetas.cancelado(m)).startsWith("❌ <b>Cancelado</b>").contains("$1.037.500")
                .contains("Sueldo octubre 2026");
    }

    @Test
    void losBotonesSonGuardarEditarYCancelar() {
        assertThat(tarjetas.principal(m).botones()).extracting(Teclado.Boton::datos)
                .containsExactly("g:" + ID, "e:" + ID, "c:" + ID);
        assertThat(tarjetas.edicionRecibo(ID).botones()).extracting(Teclado.Boton::datos)
                .containsExactly("em:" + ID, "ef:" + ID, "ed:" + ID, "v:" + ID);
    }

    @Test
    void laPreguntaDeReemplazoDiceCuantoHabiaCargado() {
        tarjetas.usarRecibos(id -> Optional.of(vista(null, DESCUENTOS)));
        Movimiento anterior = Movimiento.ingreso(ClaseIngreso.SUELDO, new BigDecimal("1030000"), null, null, null,
                AHORA, Origen.TEXTO, null, AHORA);

        String texto = tarjetas.preguntaReemplazo(m, anterior);

        assertThat(texto).endsWith("❓ <b>Ya cargaste el sueldo de octubre 2026 ($1.030.000). ¿Lo reemplazo con el "
                + "del recibo?</b>");
        assertThat(tarjetas.reemplazo(ID).botones()).extracting(Teclado.Boton::datos)
                .containsExactly("rr:" + ID, "c:" + ID);
    }
}
