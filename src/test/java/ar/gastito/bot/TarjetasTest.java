package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.CATEGORIAS;
import static ar.gastito.bot.DatosDePrueba.ID;
import static ar.gastito.bot.DatosDePrueba.KIOSCO;
import static ar.gastito.bot.DatosDePrueba.MEDIOS;
import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static ar.gastito.bot.DatosDePrueba.SUPER;
import static ar.gastito.bot.DatosDePrueba.conCategoria;
import static ar.gastito.bot.DatosDePrueba.confirmado;
import static ar.gastito.bot.DatosDePrueba.deAudio;
import static ar.gastito.bot.DatosDePrueba.dudoso;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.ticket.ControlesTicket;

class TarjetasTest {

    private final Tarjetas tarjetas = new Tarjetas(new FormatoMensajes(RELOJ));

    @Test
    void tarjetaDeUnaCuotaMuestraElNumeroYElTotal() {
        Movimiento m = conCategoria("14000", KIOSCO);
        m.prepararCuota(9L, 1, 6);

        assertThat(tarjetas.pendiente(m)).isEqualTo("""
                🧾 <b>Nuevo gasto</b>
                💰 <b>$14.000</b>
                📝 Pan de pancho y papas (cuota 1 de 6)
                🗂 Cuota 1 de 6 · total $84.000
                🍬 Kiosco
                💳 Efectivo
                📅 Hoy 16:42""");
    }

    @Test
    void tarjetaConUnDatoPorLinea() {
        assertThat(tarjetas.pendiente(conCategoria("3700", KIOSCO))).isEqualTo("""
                🧾 <b>Nuevo gasto</b>
                💰 <b>$3.700</b>
                📝 Pan de pancho y papas
                🍬 Kiosco
                💳 Efectivo
                📅 Hoy 16:42""");
    }

    @Test
    void tarjetaDudosaYSinMonto() {
        assertThat(tarjetas.pendiente(dudoso(null, SUPER, KIOSCO))).isEqualTo("""
                🧾 <b>Nuevo gasto</b>
                💰 <i>Falta el monto</i>
                📝 Pan de pancho y papas
                🏷 <b>¿Qué categoría?</b>
                💳 Efectivo
                📅 Hoy 16:42""");
        assertThat(tarjetas.pidiendoMontoFaltante(dudoso(null, SUPER))).endsWith("¿Cuánto fue?</b> Escribime el monto.");
    }

    @Test
    void guardadaConElTotalDelMes() {
        assertThat(tarjetas.guardado(confirmado("3700", SUPER), new BigDecimal("5200"))).isEqualTo("""
                ✅ <b>Guardado</b>
                💰 <b>$3.700</b>
                📝 Pan de pancho y papas
                🛒 Supermercado
                💳 Efectivo
                📅 Hoy 16:42

                📊 En octubre 2026 llevás <b>$5.200</b> en 🛒 Supermercado.""");
    }

    @Test
    void canceladaYTerminada() {
        assertThat(tarjetas.cancelado(conCategoria("3700", KIOSCO))).startsWith("❌ <b>Cancelado</b>\n💰");
        assertThat(tarjetas.terminado(confirmado("3700", KIOSCO))).startsWith("✅ <b>Guardado</b>\n💰");
    }

    @Test
    void pidiendoUnCampo() {
        assertThat(tarjetas.pidiendo(conCategoria("3700", KIOSCO), Tarjetas.Campo.FECHA))
                .startsWith("🧾 <b>Nuevo gasto</b>")
                .contains("✏️ <b>Escribí la nueva fecha</b>");
    }

    @Test
    void deUnAudioMuestraLoQueEscuchoArriba() {
        assertThat(tarjetas.pendiente(deAudio("3700", SUPER, "Gasto 3700 en pan de pancho y papas, efectivo")))
                .isEqualTo("""
                        🎙 Escuché: "Gasto 3700 en pan de pancho y papas, efectivo"

                        🧾 <b>Nuevo gasto</b>
                        💰 <b>$3.700</b>
                        📝 Pan de pancho y papas
                        🛒 Supermercado
                        💳 Efectivo
                        📅 Hoy 16:42""");
    }

    @Test
    void loQueEscuchoQuedaEnTodosLosEstados() {
        Movimiento m = deAudio("3700", SUPER, "super 3700");

        assertThat(tarjetas.pidiendo(m, Tarjetas.Campo.MONTO)).startsWith("🎙 Escuché: \"super 3700\"\n\n🧾");
        assertThat(tarjetas.cancelado(m)).startsWith("🎙 Escuché: \"super 3700\"\n\n❌ <b>Cancelado</b>");
        m.confirmar();
        assertThat(tarjetas.guardado(m, BigDecimal.TEN)).startsWith("🎙 Escuché: \"super 3700\"\n\n✅ <b>Guardado</b>");
        assertThat(tarjetas.terminado(m)).startsWith("🎙 Escuché: \"super 3700\"\n\n✅ <b>Guardado</b>");
    }

    @Test
    void loQueEscuchoSeEscapaComoHtml() {
        assertThat(tarjetas.pendiente(deAudio("3700", SUPER, "pan <b>&</b> papas")))
                .startsWith("🎙 Escuché: \"pan &lt;b&gt;&amp;&lt;/b&gt; papas\"");
    }

    @Test
    void unGastoEscritoNoMuestraLoEscuchado() {
        assertThat(tarjetas.pendiente(conCategoria("3700", KIOSCO))).doesNotContain("🎙");
    }

    @Test
    void propuestaDeUnAudioParaElCampoEnEdicion() {
        assertThat(tarjetas.propuesta(conCategoria("3700", KIOSCO), "3700", Tarjetas.Campo.MONTO, "$3.700"))
                .startsWith("🧾 <b>Nuevo gasto</b>")
                .endsWith("\n\n🎙 Escuché: \"3700\" → <b>Monto: $3.700</b>");
        assertThat(tarjetas.propuesta(ID).botones()).containsExactly(
                new Teclado.Boton("✅ Aplicar", "a:15"),
                new Teclado.Boton("↩ Cancelar edición", "v:15"));
    }

    // --- Botones ---

    @Test
    void completoMuestraGuardar() {
        assertThat(tarjetas.principal(conCategoria("3700", KIOSCO)).botones()).containsExactly(
                new Teclado.Boton("✅ Guardar", "g:15"),
                new Teclado.Boton("✏️ Editar", "e:15"),
                new Teclado.Boton("❌ Cancelar", "c:15"));
    }

    @Test
    void sinCategoriaMuestraLasCandidatasYNoGuardar() {
        Teclado t = tarjetas.principal(dudoso("3700", SUPER, KIOSCO));

        assertThat(t.filas()).hasSize(4);
        assertThat(t.botones()).extracting(Teclado.Boton::texto)
                .containsExactly("🛒 Supermercado", "🍬 Kiosco", "Otra…", "✏️ Editar", "❌ Cancelar")
                .doesNotContain("✅ Guardar");
        assertThat(t.botones()).extracting(Teclado.Boton::datos).startsWith("k:15:2", "k:15:1", "o:15");
    }

    @Test
    void sinMontoMuestraPonerMontoYNoGuardar() {
        assertThat(tarjetas.principal(conCategoria(null, KIOSCO)).botones()).extracting(Teclado.Boton::datos)
                .containsExactly("em:15", "e:15", "c:15");
    }

    @Test
    void edicionPorCampo() {
        assertThat(tarjetas.edicion(ID).botones()).extracting(Teclado.Boton::texto).containsExactly(
                "🏷 Categoría", "💰 Monto", "💳 Medio de pago", "📅 Fecha", "📝 Descripción", "↩ Volver");
    }

    @Test
    void todasLasCategoriasDeADos() {
        Teclado t = tarjetas.categorias(ID, CATEGORIAS);

        assertThat(t.filas()).extracting(java.util.List::size).containsExactly(2, 1, 1);
        assertThat(t.botones()).extracting(Teclado.Boton::datos).containsExactly("k:15:1", "k:15:2", "k:15:3", "v:15");
    }

    @Test
    void mediosConSinMedio() {
        assertThat(tarjetas.medios(ID, MEDIOS).botones()).extracting(Teclado.Boton::datos)
                .containsExactly("p:15:1", "p:15:2", "p:15:0", "v:15");
    }

    @Test
    void losDatosDeLosBotonesEntranEnTelegram() {
        assertThat(tarjetas.categorias(Long.MAX_VALUE, CATEGORIAS).botones())
                .allSatisfy(b -> assertThat(b.datos().getBytes()).hasSizeLessThanOrEqualTo(64));
    }

    // --- Tickets ---

    @Test
    void tarjetaDeUnTicketComoElPedido() {
        assertThat(tarjetas.pendiente(DatosDePrueba.ticket("8050", SUPER, DatosDePrueba.PRODUCTOS))).isEqualTo("""
                🧾 <b>Ticket</b> · Supermercado Día
                📅 sáb 10/10 17:20
                🛒 <b>Productos</b>
                <pre>Leche 1 L ×2 .......... $2.900
                Yerba 500 g ........... $3.900
                Galletitas ............ $1.250</pre>
                💰 Total <b>$8.050</b>
                💳 Débito
                🛒 Supermercado""");
    }

    @Test
    void losRenglonesTienenTodosElMismoAnchoConElPrecioALaDerecha() {
        Movimiento m = DatosDePrueba.ticket("1", SUPER, List.of(
                item("Un producto con un nombre larguísimo que no entra", "123456.78"),
                item("Pan", "1"),
                new ItemTicket("Queso", new BigDecimal("0.532"), new BigDecimal("5990"), new BigDecimal("3186.68")),
                item("2x1 Galletitas", "-250")));

        assertThat(m.getItems()).extracting(tarjetas::renglon)
                .allSatisfy(r -> assertThat(r).hasSize(Tarjetas.ANCHO_RENGLON))
                .containsExactly(
                        "Un producto con… . $123.456,78",
                        "Pan ....................... $1",
                        "Queso ×0,532 ....... $3.186,68",
                        "2x1 Galletitas ......... -$250");
    }

    @Test
    void muestraHastaOchoProductosYCuantosMasHay() {
        List<ItemTicket> diez = IntStream.rangeClosed(1, 10).mapToObj(i -> item("Producto " + i, "100")).toList();

        String html = tarjetas.pendiente(DatosDePrueba.ticket("1000", SUPER, diez));

        assertThat(html).contains("Producto 8 ").doesNotContain("Producto 9 ").contains("…y 2 más</pre>");
    }

    @Test
    void siLosProductosNoEntranEnElMensajeMuestraSoloElTotalYElResumen() {
        // Con 8 renglones recortados a 30 caracteres siempre entra en Telegram: se prueba con un límite chico.
        Tarjetas chicas = new Tarjetas(new FormatoMensajes(RELOJ), 200);
        List<ItemTicket> items = List.of(item("Leche", "100"), item("Yerba", "100"), item("Pan", "100"),
                item("Queso", "100"), item("Desc", "-50"));

        String html = chicas.pendiente(DatosDePrueba.ticket("350", SUPER, items));

        assertThat(html).doesNotContain("<pre>").contains("🛒 4 productos y 1 descuento\n💰 Total <b>$350</b>");
    }

    @Test
    void elAvisoDeLaSumaSoloAparecePendiente() {
        Movimiento m = DatosDePrueba.ticket("9000", SUPER, DatosDePrueba.PRODUCTOS);

        assertThat(tarjetas.pendiente(m)).contains("⚠️ Los productos suman <b>$8.050</b>");
        m.confirmar();
        assertThat(tarjetas.guardado(m, new BigDecimal("9000"))).doesNotContain("⚠️")
                .startsWith("✅ <b>Guardado</b> · Supermercado Día\n📅");
    }

    @Test
    void unTicketArrugadoDiceQueCuentaNoCierraYQueLineaNoSeLeyo() {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(new BigDecimal("3600"), "Día", SUPER,
                List.of(), null, null, null, DatosDePrueba.FECHA_TICKET);
        Movimiento m = Movimiento.deTicket(interpretado, new DatosTicket("Día", "5601", new BigDecimal("4500"),
                new BigDecimal("-900"), List.of("Renglón 1, Galletitas: el subtotal está sobre un pliegue"),
                List.of(item("Galletitas", "4300"), item("20% ClubDía", "-900"))), null, DatosDePrueba.AHORA);

        assertThat(tarjetas.pendiente(m)).endsWith("""
                💰 Total <b>$3.600</b>
                💳 Sin medio de pago
                🛒 Supermercado

                ⚠️ Los productos suman <b>$4.300</b> y el subtotal dice <b>$4.500</b>.
                🔍 No pude leer bien:
                • Renglón 1, Galletitas: el subtotal está sobre un pliegue
                Revisalo antes de guardar.""");
    }

    @Test
    void siLasCuentasNoCierranNombraElRenglonDondeCantidadPorPrecioNoDaElImporte() {
        // Como con la foto real de Día: el pliegue hizo leer $4.463,90 donde dice $4.488.
        MovimientoInterpretado interpretado = new MovimientoInterpretado(new BigDecimal("8238"), "Día", SUPER,
                List.of(), null, null, null, DatosDePrueba.FECHA_TICKET);
        Movimiento m = Movimiento.deTicket(interpretado, new DatosTicket("Día", "5601", new BigDecimal("8238"), null,
                List.of(), List.of(
                        new ItemTicket("Pasta mac & cheese", new BigDecimal("1"), new BigDecimal("4488"),
                                new BigDecimal("4463.90")),
                        new ItemTicket("Queso", new BigDecimal("0.625"), new BigDecimal("6000"), new BigDecimal("3750")))),
                null, DatosDePrueba.AHORA);

        assertThat(tarjetas.pendiente(m)).contains("""
                ⚠️ Los productos suman <b>$8.213,90</b> y el subtotal dice <b>$8.238</b>.
                ⚠️ Pasta mac &amp; cheese: 1 × $4.488 da <b>$4.488</b>, pero el renglón dice <b>$4.463,90</b>.
                Revisalo antes de guardar.""");
    }

    @Test
    void siLasCuentasCierranNoNombraRenglones() {
        // Un renglón que no da (una promo por cantidad, por ejemplo) no se muestra si las cuentas del ticket cierran.
        MovimientoInterpretado interpretado = new MovimientoInterpretado(new BigDecimal("4000"), "Día", SUPER,
                List.of(), null, null, null, DatosDePrueba.FECHA_TICKET);
        Movimiento m = Movimiento.deTicket(interpretado, DatosTicket.de("Día", List.of(new ItemTicket("Agua x 6",
                new BigDecimal("6"), new BigDecimal("800"), new BigDecimal("4000")))), null, DatosDePrueba.AHORA);

        assertThat(tarjetas.pendiente(m)).doesNotContain("⚠️");
    }

    @Test
    void cadaControlTieneSuFrase() {
        assertThat(tarjetas.diferencia(new ControlesTicket.Diferencia(ControlesTicket.Control.DESCUENTOS,
                new BigDecimal("-6574.60"), new BigDecimal("-7666.60"))))
                .isEqualTo("Los descuentos suman <b>$6.574,60</b> y el ticket dice <b>$7.666,60</b>.");
        assertThat(tarjetas.diferencia(new ControlesTicket.Diferencia(ControlesTicket.Control.SUBTOTAL_Y_TOTAL,
                new BigDecimal("30666.40"), new BigDecimal("36666.40"))))
                .isEqualTo("El subtotal menos los descuentos da <b>$30.666,40</b> y el total dice <b>$36.666,40</b>.");
    }

    @Test
    void unTicketSinProductosMuestraSoloElTotal() {
        assertThat(tarjetas.pendiente(DatosDePrueba.ticket("1500", KIOSCO, List.of())))
                .doesNotContain("🛒 <b>Productos</b>").contains("💰 Total <b>$1.500</b>").doesNotContain("⚠️");
    }

    private static ItemTicket item(String descripcion, String subtotal) {
        return new ItemTicket(descripcion, null, null, new BigDecimal(subtotal));
    }
}
