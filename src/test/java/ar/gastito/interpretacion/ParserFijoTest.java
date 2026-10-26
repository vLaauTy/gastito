package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;

class ParserFijoTest {

    static final Categoria SEGUROS = new Categoria(9L, "Seguros", "seguro,seguros", "Seguros", "🛡️");
    static final Categoria SERVICIOS = new Categoria(6L, "Servicios (luz, gas, servicios sociales)",
            "luz,gas,agua,servicios,internet", "Servicios", "💡");
    static final Categoria SUSCRIPCIONES = new Categoria(8L, "Suscripciones", "suscripcion,netflix,spotify",
            "Suscripciones", "📺");

    private final ParserFijo parser = new ParserFijo();
    private final Catalogo catalogo = new Catalogo(List.of(SEGUROS, SERVICIOS, SUSCRIPCIONES, CatalogoDePrueba.OTROS),
            List.of(CatalogoDePrueba.EFECTIVO, CatalogoDePrueba.DEBITO, CatalogoDePrueba.CREDITO, CatalogoDePrueba.QR),
            List.of(CatalogoDePrueba.GALICIA, CatalogoDePrueba.MERCADO_PAGO), Optional.empty());

    private PedidoFijo leer(String texto) {
        return parser.parsear(texto, catalogo).orElseThrow();
    }

    @Test
    void elSeguroConTarjetaElDia4() {
        PedidoFijo f = leer("seguro 32900 tarjeta día 4");

        assertThat(f.nombre()).isEqualTo("Seguro");
        assertThat(f.monto()).isEqualByComparingTo("32900");
        assertThat(f.categoria()).isEqualTo(SEGUROS);
        assertThat(f.medioPago()).isEqualTo(CatalogoDePrueba.CREDITO);
        assertThat(f.diaDelMes()).isEqualTo(4);
        assertThat(f.bimestral()).isFalse();
        assertThat(f.variable()).isFalse();
    }

    @Test
    void montosALaArgentinaYConMil() {
        assertThat(leer("netflix 12 mil tarjeta dia 7").monto()).isEqualByComparingTo("12000");
        assertThat(leer("netflix $11.999 tarjeta el 7").monto()).isEqualByComparingTo("11999");
        assertThat(leer("netflix 11.999,50 el 7").monto()).isEqualByComparingTo("11999.50");
    }

    @Test
    void laLuzEsVariableYPuedeSerBimestral() {
        PedidoFijo luz = leer("luz 28.000 débito galicia el 10 bimestral");

        assertThat(luz.variable()).isTrue();
        assertThat(luz.bimestral()).isTrue();
        assertThat(luz.categoria()).isEqualTo(SERVICIOS);
        assertThat(luz.medioPago()).isEqualTo(CatalogoDePrueba.DEBITO);
        assertThat(luz.cuenta()).isEqualTo(CatalogoDePrueba.GALICIA);
        assertThat(leer("gas 15000 el 20 cada dos meses").bimestral()).isTrue();
        assertThat(leer("expensas 90000 variable el 10").variable()).isTrue();
    }

    @Test
    void elNombreEsLoQueSobraYLaCategoriaPuedeQuedarSinSaber() {
        PedidoFijo f = leer("cuota del gimnasio 25000 efectivo el 5");

        assertThat(f.nombre()).isEqualTo("Cuota del gimnasio");
        assertThat(f.categoria()).isNull();
        assertThat(f.medioPago()).isEqualTo(CatalogoDePrueba.EFECTIVO);
    }

    @Test
    void sinDiaQuedaVacioParaPreguntarlo() {
        assertThat(leer("spotify 4999 tarjeta").diaDelMes()).isNull();
        // "el 40" no es un día.
        assertThat(leer("seguro 32900 el 40").diaDelMes()).isNull();
    }

    @Test
    void sinMontoOSinNombreNoEsUnFijo() {
        assertThat(parser.parsear("seguro tarjeta día 4", catalogo)).isEmpty();
        assertThat(parser.parsear("32900 día 4", catalogo)).isEmpty();
        assertThat(parser.parsear("", catalogo)).isEmpty();
    }
}
