package ar.gastito.bot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.Origen;

/** Categorías, medios y movimientos con id para los tests del bot. */
final class DatosDePrueba {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Martes 13/10/2026 16:42. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);
    static final Clock RELOJ = Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);

    static final Categoria KIOSCO = new Categoria(1L, "Kiosco", "kiosco", null, "🍬");
    static final Categoria SUPER = new Categoria(2L, "Supermercado", "super", null, "🛒");
    static final Categoria SALIDAS = new Categoria(3L, "Salidas", "salidas", null, "🍻");
    static final List<Categoria> CATEGORIAS = List.of(KIOSCO, SUPER, SALIDAS);

    static final MedioPago EFECTIVO = conId(new MedioPago("Efectivo", "efectivo"), 1L);
    static final MedioPago DEBITO = conId(new MedioPago("Débito", "debito"), 2L);
    static final List<MedioPago> MEDIOS = List.of(EFECTIVO, DEBITO);

    static final long ID = 15;

    private DatosDePrueba() {
    }

    static <T> T conId(T entidad, long id) {
        ReflectionTestUtils.setField(entidad, "id", id);
        return entidad;
    }

    /** Pendiente con categoría elegida. */
    static Movimiento conCategoria(String monto, Categoria categoria) {
        return movimiento(new MovimientoInterpretado(monto == null ? null : new BigDecimal(monto),
                "Pan de pancho y papas", categoria, List.of(), EFECTIVO, null, null, AHORA));
    }

    /** Pendiente dudoso: sin categoría, con candidatas. */
    static Movimiento dudoso(String monto, Categoria... candidatas) {
        return movimiento(new MovimientoInterpretado(monto == null ? null : new BigDecimal(monto),
                "Pan de pancho y papas", null, List.of(candidatas), EFECTIVO, null, null, AHORA));
    }

    static Movimiento confirmado(String monto, Categoria categoria) {
        Movimiento m = conCategoria(monto, categoria);
        m.confirmar();
        return m;
    }

    /** Pendiente con categoría elegida que vino de un audio. */
    static Movimiento deAudio(String monto, Categoria categoria, String escuchado) {
        return conId(Movimiento.pendiente(new MovimientoInterpretado(new BigDecimal(monto), "Pan de pancho y papas",
                categoria, List.of(), EFECTIVO, null, null, AHORA), Origen.AUDIO, escuchado, AHORA), ID);
    }

    private static Movimiento movimiento(MovimientoInterpretado interpretado) {
        return conId(Movimiento.pendiente(interpretado, Origen.TEXTO, "x", AHORA), ID);
    }
}
