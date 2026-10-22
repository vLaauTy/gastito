package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.GALICIA;
import static ar.gastito.interpretacion.CatalogoDePrueba.MERCADO_PAGO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ar.gastito.catalogo.Catalogo;

class ParserIngresoTest {

    private final ParserIngreso parser = new ParserIngreso();
    private final Catalogo catalogo = CatalogoDePrueba.crear();

    private ParserIngreso.Resultado leer(String texto) {
        Optional<ParserIngreso.Resultado> r = parser.parsear(texto, catalogo);
        assertThat(r).as("leer \"%s\"", texto).isPresent();
        return r.get();
    }

    @Test
    void soloElMonto() {
        ParserIngreso.Resultado r = leer("380.000");

        assertThat(r.monto()).isEqualByComparingTo("380000");
        assertThat(r.descripcion()).isNull();
        assertThat(r.cuenta()).isNull();
        assertThat(r.efectivo()).isFalse();
        assertThat(r.fecha()).isEmpty();
    }

    @Test
    void montoConPalabrasYCuenta() {
        ParserIngreso.Resultado r = leer("380 mil galicia");

        assertThat(r.monto()).isEqualByComparingTo("380000");
        assertThat(r.cuenta()).isEqualTo(GALICIA);
        assertThat(r.descripcion()).isNull();
    }

    @Test
    void descripcionYCuentaDeDosPalabras() {
        ParserIngreso.Resultado r = leer("25000 arreglo de PC a mercado pago");

        assertThat(r.monto()).isEqualByComparingTo("25000");
        assertThat(r.descripcion()).isEqualTo("arreglo de PC");
        assertThat(r.cuenta()).isEqualTo(MERCADO_PAGO);
    }

    @Test
    void cuentaPorPalabraClave() {
        assertThat(leer("25k venta de la bici, mp").cuenta()).isEqualTo(MERCADO_PAGO);
        assertThat(leer("25k venta de la bici, mp").descripcion()).isEqualTo("venta de la bici");
    }

    @Test
    void enEfectivoYAyer() {
        ParserIngreso.Resultado r = leer("5000 regalo de la abuela en efectivo ayer");

        assertThat(r.efectivo()).isTrue();
        assertThat(r.cuenta()).isNull();
        assertThat(r.fecha()).isEqualTo("ayer");
        assertThat(r.descripcion()).isEqualTo("regalo de la abuela");
    }

    @Test
    void elMontoPuedeIrDespuesSiEsElUnico() {
        ParserIngreso.Resultado r = leer("arreglo de PC 25000");

        assertThat(r.monto()).isEqualByComparingTo("25000");
        assertThat(r.descripcion()).isEqualTo("arreglo de PC");
    }

    @Test
    void conElMontoPrimeroLosNumerosDeLaDescripcionNoMolestan() {
        ParserIngreso.Resultado r = leer("30000 arreglo de 2 PCs");

        assertThat(r.monto()).isEqualByComparingTo("30000");
        assertThat(r.descripcion()).isEqualTo("arreglo de 2 PCs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "sueldo", "arreglo de 2 PCs por 30000", "galicia"})
    void sinUnMontoClaroNoLee(String texto) {
        assertThat(parser.parsear(texto, catalogo)).isEmpty();
    }
}
