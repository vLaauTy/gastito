package ar.gastito.viaje;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Hoy es el 6/11/2026. */
class ParserViajeTest {

    static final LocalDate HOY = LocalDate.of(2026, 11, 6);

    private final Viaje bariloche = new Viaje("Bariloche", null, LocalDate.of(2027, 1, 10),
            LocalDate.of(2027, 1, 17), null, null, null, LocalDateTime.of(2026, 11, 1, 10, 0));
    private final List<Viaje> viajes = List.of(bariloche);

    private Optional<ParserViaje.Pedido> parsear(String texto) {
        return ParserViaje.parsear(texto, viajes, HOY);
    }

    @Test
    void unViajeNuevoConFechasPersonasYPresupuesto() {
        assertThat(parsear("Me voy a San Martín de los Andes del 10/1 al 17/1, somos 4, presupuesto 1.500.000"))
                .contains(new ParserViaje.Nuevo("San Martín de los Andes", LocalDate.of(2027, 1, 10),
                        LocalDate.of(2027, 1, 17), 4, new BigDecimal("1500000")));
    }

    @Test
    void lasFechasConElMesDichoUnaVez() {
        assertThat(parsear("nos vamos a Mendoza del 28 de diciembre al 3 de enero")).contains(
                new ParserViaje.Nuevo("Mendoza", LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3), null, null));
        assertThat(parsear("viaje a Córdoba del 20 al 23 de noviembre")).contains(
                new ParserViaje.Nuevo("Córdoba", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 23), null, null));
    }

    @Test
    void loQueFaltaPagarConYSinMonto() {
        assertThat(parsear("para Bariloche me falta el hospedaje, ≈ 400.000"))
                .contains(new ParserViaje.Falta(bariloche, "hospedaje", new BigDecimal("400000")));
        assertThat(parsear("para bariloche falta pagar las excursiones"))
                .contains(new ParserViaje.Falta(bariloche, "excursiones", null));
        assertThat(parsear("para Bariloche me faltan los traslados, unos 60 mil"))
                .contains(new ParserViaje.Falta(bariloche, "traslados", new BigDecimal("60000")));
    }

    @Test
    void unViajeQueNoExisteNoEsUnPendiente() {
        assertThat(parsear("para el asado me falta el carbón 5000")).isEmpty();
    }

    @Test
    void lasConsultas() {
        assertThat(parsear("/viajes")).contains(new ParserViaje.Consulta(null));
        assertThat(parsear("¿Cómo va Bariloche?")).contains(new ParserViaje.Consulta(bariloche));
        assertThat(parsear("cuánto tengo que ahorrar para bariloche")).contains(new ParserViaje.Consulta(bariloche));
    }

    @Test
    void unGastoNormalNoEsDeViajes() {
        assertThat(parsear("kiosco 1500")).isEmpty();
        assertThat(parsear("pagué 12000 de nafta en Bariloche")).isEmpty();
    }
}
