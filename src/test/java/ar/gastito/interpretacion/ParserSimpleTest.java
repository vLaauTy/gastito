package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.CREDITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.EFECTIVO;
import static ar.gastito.interpretacion.CatalogoDePrueba.KIOSCO;
import static ar.gastito.interpretacion.CatalogoDePrueba.MERCADO_PAGO;
import static ar.gastito.interpretacion.CatalogoDePrueba.QR;
import static ar.gastito.interpretacion.CatalogoDePrueba.SUPER;
import static ar.gastito.interpretacion.CatalogoDePrueba.TRANSPORTE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ar.gastito.catalogo.Catalogo;

class ParserSimpleTest {

    private final ParserSimple parser = new ParserSimple();
    private final Catalogo catalogo = CatalogoDePrueba.crear();

    private Optional<ParserSimple.Resultado> parsear(String texto) {
        return parser.parsear(texto, catalogo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kiosco 1500", "1500 kiosco", "Kiosco 1500", "KIOSCO $1.500", "  kiosco   1500  ",
            "kiosko 1500", "almacén 1500", "kiosco 1500,00", "kiosco 1500."})
    void categoriaYMontoEnCualquierOrden(String texto) {
        ParserSimple.Resultado r = parsear(texto).orElseThrow();
        assertThat(r.monto()).isEqualByComparingTo("1500");
        assertThat(r.categoria()).isSameAs(KIOSCO);
        assertThat(r.medioPago()).isNull();
        assertThat(r.cuenta()).isNull();
    }

    @Test
    void laDescripcionEsLaPalabraQueUsoElUsuario() {
        assertThat(parsear("nafta 12000").orElseThrow().descripcion()).isEqualTo("Nafta");
        assertThat(parsear("nafta 12000").orElseThrow().categoria()).isSameAs(TRANSPORTE);
    }

    @Test
    void montoConDecimales() {
        assertThat(parsear("super 2.500,50").orElseThrow().monto()).isEqualByComparingTo("2500.50");
    }

    @Test
    void aceptaMedioDePago() {
        ParserSimple.Resultado r = parsear("kiosco 1500 efectivo").orElseThrow();
        assertThat(r.medioPago()).isSameAs(EFECTIVO);

        assertThat(parsear("débito super 3000")).isPresent();
        assertThat(parsear("super 3000 tarjeta").orElseThrow().medioPago()).isSameAs(CREDITO);
    }

    @Test
    void aceptaMedioDePagoYCuenta() {
        ParserSimple.Resultado r = parsear("super 8000 qr mp").orElseThrow();
        assertThat(r.categoria()).isSameAs(SUPER);
        assertThat(r.medioPago()).isSameAs(QR);
        assertThat(r.cuenta()).isSameAs(MERCADO_PAGO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "3700 en pan de pancho y papas, efectivo", // palabras de más
            "pagué 12000 de nafta con la tarjeta",
            "kiosco", // sin monto
            "1500", // sin categoría
            "kiosco 1500 2000", // dos montos
            "kiosco super 1500", // dos categorías
            "kiosco 1500 efectivo debito", // dos medios
            "kiosco 1500 ayer", // fechas: las resuelve el otro camino
            "kiosco 0",
            "",
            "   "
    })
    void loQueNoEsObvioNoSeResuelve(String texto) {
        assertThat(parsear(texto)).isEmpty();
    }

    // --- separar: para buscar el resto en el historial ---

    @Test
    void separaMontoMedioYResto() {
        ParserSimple.Separado s = parser.separar("3700 en pan de pancho y papas, efectivo", catalogo).orElseThrow();

        assertThat(s.monto()).isEqualByComparingTo("3700");
        assertThat(s.medioPago()).isSameAs(EFECTIVO);
        assertThat(s.cuenta()).isNull();
        assertThat(s.fecha()).isEmpty();
        assertThat(s.resto()).isEqualTo("en pan de pancho y papas");
    }

    @Test
    void separaCuentaYFechaSimple() {
        ParserSimple.Separado s = parser.separar("ayer pan y leche 2.500 qr mp", catalogo).orElseThrow();

        assertThat(s.monto()).isEqualByComparingTo("2500");
        assertThat(s.medioPago()).isSameAs(QR);
        assertThat(s.cuenta()).isSameAs(MERCADO_PAGO);
        assertThat(s.fecha()).isEqualTo("ayer");
        assertThat(s.resto()).isEqualTo("pan y leche");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pan de pancho", "1500", "1500 efectivo", "1500 2000 pan", "", "  "})
    void sinMontoUnicoOSinRestoNoSepara(String texto) {
        assertThat(parser.separar(texto, catalogo)).isEmpty();
    }

    @Test
    void nullNoSeResuelve() {
        assertThat(parser.parsear(null, catalogo)).isEmpty();
    }
}
