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

import org.junit.jupiter.api.Test;

import ar.gastito.movimiento.Movimiento;

class TarjetasTest {

    private final Tarjetas tarjetas = new Tarjetas(new FormatoMensajes(RELOJ));

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
}
