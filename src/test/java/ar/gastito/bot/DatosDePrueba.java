package ar.gastito.bot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.ItemTicket;
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

    static final MedioPago EFECTIVO = efectivo(conId(new MedioPago("Efectivo", "efectivo"), 1L));
    static final MedioPago DEBITO = conId(new MedioPago("Débito", "debito"), 2L);
    static final List<MedioPago> MEDIOS = List.of(EFECTIVO, DEBITO);

    static final long ID = 15;

    private DatosDePrueba() {
    }

    static <T> T conId(T entidad, long id) {
        ReflectionTestUtils.setField(entidad, "id", id);
        return entidad;
    }

    private static MedioPago efectivo(MedioPago medio) {
        ReflectionTestUtils.setField(medio, "efectivo", true);
        return medio;
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

    /** Los productos del ticket del pedido: suman $8.050. */
    static final List<ItemTicket> PRODUCTOS = List.of(
            new ItemTicket("Leche 1 L", new BigDecimal("2"), new BigDecimal("1450.00"), new BigDecimal("2900.00")),
            new ItemTicket("Yerba 500 g", null, null, new BigDecimal("3900.00")),
            new ItemTicket("Galletitas", null, null, new BigDecimal("1250.00")));

    /** Sábado 10/10 17:20, cuando se compró el ticket. */
    static final LocalDateTime FECHA_TICKET = LocalDateTime.of(2026, 10, 10, 17, 20);

    /** Un ticket pendiente del Supermercado Día con débito; la categoría puede quedar sin elegir. */
    static Movimiento ticket(String total, Categoria categoria, List<ItemTicket> items, Categoria... candidatas) {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(new BigDecimal(total), "Supermercado Día",
                categoria, List.of(candidatas), DEBITO, null, null, FECHA_TICKET);
        return conId(Movimiento.deTicket(interpretado, "Supermercado Día", items, null, AHORA), ID);
    }

    private static Movimiento movimiento(MovimientoInterpretado interpretado) {
        return conId(Movimiento.pendiente(interpretado, Origen.TEXTO, "x", AHORA), ID);
    }
}
