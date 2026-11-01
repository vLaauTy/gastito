package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Hoy es el sábado 31/10/2026: el último resumen cerró el 29/10 y vence el 11/11. */
class ParserTarjetaTest {

    static final LocalDate HOY = LocalDate.of(2026, 10, 31);

    private static Optional<ValidadorInterpretacion.Resultado> parsear(String texto) {
        return ParserTarjeta.parsear(texto, HOY);
    }

    private static ValidadorInterpretacion.FechasTarjeta fechas(String texto) {
        return (ValidadorInterpretacion.FechasTarjeta) parsear(texto).orElseThrow();
    }

    // --- Fechas ---

    @Test
    void lasFechasDelProximoResumen() {
        ValidadorInterpretacion.FechasTarjeta f = fechas("la tarjeta cierra el 26/11 y vence el 11/12");

        assertThat(f.cierre()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(f.vencimiento()).isEqualTo(LocalDate.of(2026, 12, 11));
    }

    @Test
    void elVencimientoEsDespuesDelCierreAunqueCambieElAnio() {
        ValidadorInterpretacion.FechasTarjeta f = fechas("Cierre 28/12, vencimiento 11/1");

        assertThat(f.cierre()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(f.vencimiento()).isEqualTo(LocalDate.of(2027, 1, 11));
    }

    @Test
    void enPasadoEsElQueYaCerro() {
        ValidadorInterpretacion.FechasTarjeta f = fechas("La tarjeta cerró el 29/10 y vence el 11/11");

        assertThat(f.cierre()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(f.vencimiento()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void conElDiaSoloOElMesEnPalabras() {
        assertThat(fechas("cierra el 26 y vence el 11").vencimiento()).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(fechas("cierra el 26 de noviembre y vence el 11 de diciembre").cierre())
                .isEqualTo(LocalDate.of(2026, 11, 26));
    }

    @Test
    void siFaltaUnaFechaVieneSinEsa() {
        ValidadorInterpretacion.FechasTarjeta f = fechas("la tarjeta cierra el 26/11");

        assertThat(f.cierre()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(f.vencimiento()).isNull();
    }

    // --- Total y débito ---

    @Test
    void elTotalDelResumen() {
        assertThat(parsear("el resumen de la tarjeta es de 250.000"))
                .contains(new ValidadorInterpretacion.TotalTarjeta(new BigDecimal("250000")));
        assertThat(parsear("el resumen vino de 250 mil"))
                .contains(new ValidadorInterpretacion.TotalTarjeta(new BigDecimal("250000")));
    }

    @Test
    void elDebitoConYSinMonto() {
        assertThat(parsear("se debitó la tarjeta, 250.000"))
                .contains(new ValidadorInterpretacion.PagoTarjeta(new BigDecimal("250000")));
        assertThat(parsear("ya se debitó el resumen")).contains(new ValidadorInterpretacion.PagoTarjeta(null));
    }

    // --- Consultas ---

    @Test
    void lasConsultasSonLaListaDeLaTarjeta() {
        ValidadorInterpretacion.Ver tarjeta = new ValidadorInterpretacion.Ver(ValidadorInterpretacion.Lista.TARJETA);

        assertThat(parsear("¿cuánto llevo de tarjeta?")).contains(tarjeta);
        assertThat(parsear("¿cuánto vence el 11?")).contains(tarjeta);
        assertThat(parsear("¿cuándo vence la tarjeta?")).contains(tarjeta);
        assertThat(parsear("¿la tarjeta vence el 11?")).contains(tarjeta);
        assertThat(parsear("tarjeta")).contains(tarjeta);
    }

    // --- Lo que no es del resumen ---

    @Test
    void unGastoConTarjetaNoEsDelResumen() {
        assertThat(parsear("pagué con la tarjeta 5000 en el super")).isEmpty();
        assertThat(parsear("zapatillas 84000 con tarjeta")).isEmpty();
        assertThat(parsear("¿cuánto gasté con tarjeta este mes?")).isEmpty();
        assertThat(parsear("¿qué cuotas tengo en la tarjeta?")).isEmpty();
        assertThat(parsear("kiosco 1500")).isEmpty();
        assertThat(parsear("mandame el resumen del mes")).isEmpty();
    }

    // --- Fechas que devuelve la IA ---

    @Test
    void lasFechasSueltasDeLaIa() {
        assertThat(ParserTarjeta.cierre("26/11", HOY)).contains(LocalDate.of(2026, 11, 26));
        assertThat(ParserTarjeta.cierre("2026-11-26", HOY)).contains(LocalDate.of(2026, 11, 26));
        assertThat(ParserTarjeta.vencimiento("el 11", LocalDate.of(2026, 11, 26))).contains(LocalDate.of(2026, 12, 11));
        assertThat(ParserTarjeta.cierre("pronto", HOY)).isEmpty();
    }
}
