package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.Catalogo;

/** Hoy es el viernes 6/11/2026. */
class PedidoPrestamoTest {

    static final LocalDate HOY = LocalDate.of(2026, 11, 6);
    static final LocalDateTime AHORA = HOY.atTime(12, 0);

    private final ValidadorInterpretacion validador = new ValidadorInterpretacion(new ResolvedorFechas());
    private final Catalogo catalogo = CatalogoDePrueba.crear();

    @Test
    void elMesSeEntiendeComoLoDice() {
        assertThat(PedidoPrestamo.mes("diciembre", HOY)).isEqualTo(YearMonth.of(2026, 12));
        assertThat(PedidoPrestamo.mes("noviembre", HOY)).isEqualTo(YearMonth.of(2026, 11));
        assertThat(PedidoPrestamo.mes("marzo", HOY)).isEqualTo(YearMonth.of(2027, 3));
        assertThat(PedidoPrestamo.mes("enero 2028", HOY)).isEqualTo(YearMonth.of(2028, 1));
        assertThat(PedidoPrestamo.mes("12/2026", HOY)).isEqualTo(YearMonth.of(2026, 12));
        assertThat(PedidoPrestamo.mes("el mes que viene", HOY)).isEqualTo(YearMonth.of(2026, 12));
        assertThat(PedidoPrestamo.mes("cuando pueda", HOY)).isNull();
    }

    @Test
    void sinPersonaOSinCuantoNoEsUnPrestamo() {
        assertThat(PedidoPrestamo.de("", "30000", "6", "", "", "", HOY)).isNull();
        assertThat(PedidoPrestamo.de("Martín", "", "6", "", "", "", HOY)).isNull();
        assertThat(PedidoPrestamo.de("martín", "", "6", "5000", "", "", HOY))
                .isEqualTo(new PedidoPrestamo("Martín", null, 6, new BigDecimal("5000.00"), null, null));
    }

    private static InterpretacionIa gastoConPrestamo(String intencion, String monto, String prestamoMonto) {
        return new InterpretacionIa(intencion, monto, "Vuelos", List.of("Otros"), "alta", "Transferencia", "", "",
                "", "", List.of(), "", "", "", "", "", "", "", "", "", "", "", "", List.of(), "", "", "", "ARS",
                "Martín", prestamoMonto, "6", "5000", "diciembre", "");
    }

    @Test
    void unGastoConPrestamoTraeElTotalYElPrestamoAparte() {
        ValidadorInterpretacion.Valida v = (ValidadorInterpretacion.Valida) validador
                .validar(gastoConPrestamo("GASTO", "293000", "30000"), catalogo, AHORA);

        assertThat(v.movimiento().monto()).isEqualByComparingTo("293000");
        assertThat(v.prestamo()).isEqualTo(new PedidoPrestamo("Martín", new BigDecimal("30000.00"), 6,
                new BigDecimal("5000.00"), YearMonth.of(2026, 12), null));
        assertThat(v.juntada()).isNull();
    }

    @Test
    void unPrestamoSoloTomaElMontoDeDondeVenga() {
        ValidadorInterpretacion.Prestamo p = (ValidadorInterpretacion.Prestamo) validador
                .validar(gastoConPrestamo("PRESTAMO", "30000", ""), catalogo, AHORA);

        assertThat(p.pedido().monto()).isEqualByComparingTo("30000");
        assertThat(p.pedido().persona()).isEqualTo("Martín");
    }
}
