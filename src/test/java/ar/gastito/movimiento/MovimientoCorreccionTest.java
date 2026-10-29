package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;

/** "El último era 3500, no 3700" y "borrá lo último": también sobre lo que ya está guardado. */
class MovimientoCorreccionTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);
    static final Categoria SUPER = new Categoria(2L, "Supermercado", "super", "Almacén", "🛒");
    static final Categoria KIOSCO = new Categoria(1L, "Kiosco", "kiosco", "Golosinas", "🍬");

    private static Movimiento guardado() {
        Movimiento m = Movimiento.pendiente(MovimientoInterpretado.conCategoria(new BigDecimal("3700"), "Pan", SUPER,
                null, null, null, AHORA), Origen.TEXTO, "3700 pan", AHORA);
        m.confirmar();
        return m;
    }

    @Test
    void seCorrigeLoGuardadoSoloEnLoQueSeDice() {
        Movimiento m = guardado();

        m.corregir(new BigDecimal("3500"), null, null, null, null, null);
        assertThat(m.getMonto()).isEqualByComparingTo("3500");
        assertThat(m.getDescripcion()).isEqualTo("Pan");
        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);

        m.corregir(null, "Pan y facturas", null, KIOSCO, new MedioPago("Débito", "debito"), null);
        assertThat(m.getDescripcion()).isEqualTo("Pan y facturas");
        assertThat(m.getCategoria()).isEqualTo(KIOSCO);
        assertThat(m.getMedioPago().getNombre()).isEqualTo("Débito");
        assertThatThrownBy(() -> m.corregir(BigDecimal.ZERO, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void borrarLoDejaCanceladoYNoSePuedeDosVeces() {
        Movimiento m = guardado();

        m.anular();

        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.CANCELADO);
        assertThatThrownBy(m::anular).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> m.corregir(BigDecimal.TEN, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
