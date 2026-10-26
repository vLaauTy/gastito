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
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.interpretacion.ParserFijo;

class ConversacionFijosTest {

    static final int MENSAJE = 100;
    static final Categoria SEGUROS = new Categoria(9L, "Seguros", "seguro,seguros", "Seguros", "🛡️");
    static final LocalDate PRIMER_COBRO = LocalDate.of(2026, 11, 4);

    private final GastoFijoService servicio = mock(GastoFijoService.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final ConversacionFijos conversacion = new ConversacionFijos(servicio, new ParserFijo(), catalogos,
            new FormatoMensajes(RELOJ));

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
                new BigDecimal("32900.00"), "Seguros", "🛡️", null, 4, Frecuencia.MENSUAL, false, true, PRIMER_COBRO));

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
    void laListaConElProximoCobroYLaBaja() {
        VistaFijo seguro = new VistaFijo(7L, "Seguro", new BigDecimal("32900.00"), new BigDecimal("32900.00"),
                "Seguros", "🛡️", "MasterCard Gold", 4, Frecuencia.MENSUAL, false, true, PRIMER_COBRO);
        VistaFijo luz = new VistaFijo(8L, "Luz", new BigDecimal("28000.00"), new BigDecimal("30500.00"), "Servicios",
                "💡", null, 10, Frecuencia.BIMESTRAL, true, true, LocalDate.of(2026, 11, 10));
        when(servicio.activos()).thenReturn(List.of(seguro, luz));

        Accion.Enviar lista = (Accion.Enviar) conversacion.lista();

        assertThat(lista.html()).isEqualTo("""
                📌 <b>Tus gastos fijos</b>
                • 🛡️ Seguro: $32.900 · próximo cobro miércoles 4/11
                • 💡 Luz: $30.500 (promedio) cada dos meses · próximo cobro martes 10/11""");
        assertThat(datos(lista.teclado())).containsExactly("fb:7", "fb:8");

        when(servicio.vista(7L)).thenReturn(seguro);
        List<Accion> baja = conversacion.boton(MENSAJE, ConversacionFijos.BAJA, 7, null);
        verify(servicio).darDeBaja(7L);
        assertThat(baja).contains(new Accion.Avisar("🗑 Seguro ya no se cobra"));
    }
}
