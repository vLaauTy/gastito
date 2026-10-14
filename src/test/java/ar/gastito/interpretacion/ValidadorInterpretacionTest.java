package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.COMIDA;
import static ar.gastito.interpretacion.CatalogoDePrueba.CREDITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.DEBITO;
import static ar.gastito.interpretacion.CatalogoDePrueba.EFECTIVO;
import static ar.gastito.interpretacion.CatalogoDePrueba.GALICIA;
import static ar.gastito.interpretacion.CatalogoDePrueba.KIOSCO;
import static ar.gastito.interpretacion.CatalogoDePrueba.MERCADO_PAGO;
import static ar.gastito.interpretacion.CatalogoDePrueba.OTROS;
import static ar.gastito.interpretacion.CatalogoDePrueba.SALUD;
import static ar.gastito.interpretacion.CatalogoDePrueba.SUPER;
import static ar.gastito.interpretacion.CatalogoDePrueba.TRANSPORTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.Tarjeta;

class ValidadorInterpretacionTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);

    private final ValidadorInterpretacion validador = new ValidadorInterpretacion(new ResolvedorFechas());
    private final Catalogo catalogo = CatalogoDePrueba.crear();

    private static InterpretacionIa ia(String monto, String descripcion, List<String> categorias, String confianza,
            String medio, String cuenta) {
        return new InterpretacionIa(true, monto, descripcion, categorias, confianza, medio, cuenta, "", "");
    }

    private static InterpretacionIa ia(String monto, String descripcion, String categoria, String medio,
            String cuenta) {
        return ia(monto, descripcion, List.of(categoria), "alta", medio, cuenta);
    }

    private ValidadorInterpretacion.Valida validar(InterpretacionIa ia) {
        ValidadorInterpretacion.Resultado r = validador.validar(ia, catalogo, AHORA);
        assertThat(r).isInstanceOf(ValidadorInterpretacion.Valida.class);
        return (ValidadorInterpretacion.Valida) r;
    }

    private MovimientoInterpretado valida(InterpretacionIa ia) {
        return validar(ia).movimiento();
    }

    // --- Candidatas y confianza ---

    @Test
    void conConfianzaAltaLaCategoriaQuedaElegida() {
        ValidadorInterpretacion.Valida v = validar(ia("12000", "Nafta", List.of("Transporte / Moto"), "alta", "", ""));

        assertThat(v.confianza()).isEqualTo(Confianza.ALTA);
        assertThat(v.movimiento().categoria()).isSameAs(TRANSPORTE);
        assertThat(v.movimiento().candidatas()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"media", "baja", "Media", "BAJA"})
    void conConfianzaMediaOBajaSePreguntaEntreLasCandidatasEnOrden(String confianza) {
        ValidadorInterpretacion.Valida v = validar(ia("3700", "Pan de pancho y papas",
                List.of("Supermercado", "Kiosco", "Comida afuera / delivery"), confianza, "Efectivo", ""));

        assertThat(v.confianza()).isNotEqualTo(Confianza.ALTA);
        assertThat(v.movimiento().categoria()).isNull();
        assertThat(v.movimiento().candidatas()).containsExactly(SUPER, KIOSCO, COMIDA);
    }

    @Test
    void descartaCandidatasInexistentesYRepetidasYSeQuedaConTres() {
        MovimientoInterpretado m = valida(ia("100", "x",
                List.of("Inventada", "Kiosco", "kiosco", "Supermercado", "Salud / farmacia", "Otros"), "media", "",
                ""));

        assertThat(m.candidatas()).containsExactly(KIOSCO, SUPER, SALUD);
    }

    @Test
    void siLaPrimeraNoExisteNoPuedeSerConfianzaAlta() {
        ValidadorInterpretacion.Valida v = validar(ia("100", "x", List.of("Electrónica", "Kiosco"), "alta", "", ""));

        assertThat(v.confianza()).isEqualTo(Confianza.BAJA);
        assertThat(v.movimiento().categoria()).isNull();
        assertThat(v.movimiento().candidatas()).containsExactly(KIOSCO);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Inventada", "Electrónica"})
    void sinCandidatasValidasSePreguntaConOtros(String categoria) {
        List<String> categorias = categoria == null ? null : Arrays.asList(categoria);
        ValidadorInterpretacion.Valida v = validar(ia("100", "Algo", categorias, "alta", "", ""));

        assertThat(v.confianza()).isEqualTo(Confianza.BAJA);
        assertThat(v.movimiento().categoria()).isNull();
        assertThat(v.movimiento().candidatas()).containsExactly(OTROS);
    }

    @ParameterizedTest
    @CsvSource(value = {"alta, ALTA", "Alta, ALTA", "media, MEDIA", "baja, BAJA", "'', BAJA", "no sé, BAJA"})
    void leeLaConfianza(String texto, Confianza esperada) {
        assertThat(ValidadorInterpretacion.confianza(texto)).isEqualTo(esperada);
        assertThat(ValidadorInterpretacion.confianza(null)).isEqualTo(Confianza.BAJA);
    }

    // --- Resto de los campos ---

    @Test
    void respuestaCompletaYCorrecta() {
        MovimientoInterpretado m = valida(ia("3700", "Pan de pancho y papas", "Kiosco", "Efectivo", ""));

        assertThat(m.monto()).isEqualByComparingTo("3700");
        assertThat(m.descripcion()).isEqualTo("Pan de pancho y papas");
        assertThat(m.categoria()).isSameAs(KIOSCO);
        assertThat(m.medioPago()).isSameAs(EFECTIVO);
        assertThat(m.cuenta()).isNull();
        assertThat(m.tarjeta()).isNull();
        assertThat(m.fechaHora()).isEqualTo(AHORA);
    }

    @Test
    void comparaNombresSinTildesNiMayusculas() {
        MovimientoInterpretado m = valida(ia("100", "x", "comida afuera / DELIVERY", "debito", "mercado pago"));
        assertThat(m.categoria()).isSameAs(COMIDA);
        assertThat(m.medioPago()).isSameAs(DEBITO);
        assertThat(m.cuenta()).isSameAs(MERCADO_PAGO);
    }

    @Test
    void aceptaUnaPalabraClaveEnLugarDelNombre() {
        assertThat(valida(ia("12000", "Nafta", "nafta", "", "")).categoria()).isSameAs(TRANSPORTE);
    }

    @Test
    void medioYCuentaQueNoExistenQuedanVacios() {
        MovimientoInterpretado m = valida(ia("100", "Algo", "Kiosco", "Bitcoin", "Banco Nación"));
        assertThat(m.medioPago()).isNull();
        assertThat(m.cuenta()).isNull();
    }

    @Test
    void conTarjetaDeCreditoAsociaLaTarjetaYNoLaCuenta() {
        Tarjeta tarjeta = mock(Tarjeta.class);
        Catalogo conTarjeta = new Catalogo(catalogo.categorias(), catalogo.mediosPago(), catalogo.cuentas(),
                Optional.of(tarjeta));

        ValidadorInterpretacion.Resultado r = validador.validar(
                ia("12000", "Nafta", "Transporte / Moto", "Tarjeta de crédito", "Galicia"), conTarjeta, AHORA);

        MovimientoInterpretado m = ((ValidadorInterpretacion.Valida) r).movimiento();
        assertThat(m.medioPago()).isSameAs(CREDITO);
        assertThat(m.tarjeta()).isSameAs(tarjeta);
        assertThat(m.cuenta()).isNull();
    }

    @Test
    void conOtroMedioRespetaLaCuenta() {
        assertThat(valida(ia("100", "x", "Kiosco", "Débito", "Galicia")).cuenta()).isSameAs(GALICIA);
    }

    @ParameterizedTest
    @CsvSource({"1250.50, 1250.50", "'1.500', 1500", "'1.500,50', 1500.50", "$ 3700, 3700", "1500.555, 1500.56",
            "15000, 15000"})
    void normalizaElMonto(String monto, String esperado) {
        MovimientoInterpretado m = valida(ia(monto, "x", "Kiosco", "", ""));
        assertThat(m.monto()).isEqualByComparingTo(esperado);
        assertThat(m.monto().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "0", "-500", "mucho", "1,500"})
    void sinMontoValidoQuedaVacioParaPedirloEnLaTarjeta(String monto) {
        MovimientoInterpretado m = valida(ia(monto, "Pan", "Kiosco", "Efectivo", ""));

        assertThat(m.monto()).isNull();
        assertThat(m.categoria()).isSameAs(KIOSCO);
        assertThat(m.medioPago()).isSameAs(EFECTIVO);
    }

    @Test
    void siNoEsGastoNoSeGuardaNada() {
        InterpretacionIa saludo = new InterpretacionIa(false, "", "", List.of(), "", "", "", "", "");
        assertThat(validador.validar(saludo, catalogo, AHORA)).isInstanceOf(ValidadorInterpretacion.NoEsGasto.class);
        assertThat(validador.validar(null, catalogo, AHORA)).isInstanceOf(ValidadorInterpretacion.NoEsGasto.class);
    }

    @Test
    void descripcionVaciaUsaLaCategoriaYLargaSeCorta() {
        assertThat(valida(ia("100", " ", "Kiosco", "", "")).descripcion()).isEqualTo("Kiosco");
        assertThat(valida(ia("100", "pan", "Kiosco", "", "")).descripcion()).isEqualTo("Pan");
        assertThat(valida(ia("100", "x".repeat(300), "Kiosco", "", "")).descripcion())
                .hasSize(ValidadorInterpretacion.LARGO_MAXIMO_DESCRIPCION);
    }

    @Test
    void resuelveLaFecha() {
        InterpretacionIa conFecha = new InterpretacionIa(true, "100", "x", List.of("Kiosco"), "alta", "", "", "ayer",
                "21:30");
        assertThat(valida(conFecha).fechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 12, 21, 30));
    }
}
