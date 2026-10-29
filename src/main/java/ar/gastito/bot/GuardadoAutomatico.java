package ar.gastito.bot;

import java.util.List;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;
import ar.gastito.ticket.ControlesTicket;
import ar.gastito.ticket.TicketService;

/**
 * Los gastos claros se guardan solos y se pueden deshacer o editar; se pide confirmación solo cuando hay una duda
 * real. Acá está la regla de cuándo un gasto es claro (las compras repetidas se buscan antes, en
 * {@link FlujoDeMensajes}), la tarjeta "✅ Guardado" y sus botones: ↩️ Deshacer y el medio de pago con un toque.
 */
class GuardadoAutomatico {

    private final BotProperties properties;
    private final MovimientoService movimientos;
    private final CatalogoService catalogos;
    private final Tarjetas tarjetas;
    private final TicketService tickets;

    GuardadoAutomatico(BotProperties properties, MovimientoService movimientos, CatalogoService catalogos,
            Tarjetas tarjetas, TicketService tickets) {
        this.properties = properties;
        this.movimientos = movimientos;
        this.catalogos = catalogos;
        this.tarjetas = tarjetas;
        this.tickets = tickets;
    }

    /**
     * Un gasto simple recién interpretado que se guarda sin preguntar: monto claro, categoría con confianza alta
     * (si no, no tendría categoría), hasta el tope, y nada de juntadas, cuotas ni fijos. Un ticket, además, con las
     * cuentas cerradas y el medio de pago impreso (un "TARJETA" a secas queda sin medio y se pregunta). El medio de
     * pago de un texto o un audio no frena el guardado: si no se dijo, se elige después con un toque.
     */
    boolean corresponde(Movimiento m) {
        if (!properties.guardadoAutomatico() || !m.isPendiente() || m.getTipo() != TipoMovimiento.GASTO) {
            return false;
        }
        if (m.getMonto() == null || m.getCategoria() == null
                || m.getMonto().compareTo(properties.montoMaximoAutomatico()) > 0) {
            return false;
        }
        if (m.isJuntada() || m.getPlanCuotasId() != null || m.getGastoFijoId() != null) {
            return false;
        }
        return m.getOrigen() == Origen.TEXTO || m.getOrigen() == Origen.AUDIO
                || (m.getOrigen() == Origen.TICKET && m.getMedioPago() != null && ControlesTicket.revisar(m).isEmpty());
    }

    /** Lo guarda y manda la tarjeta "✅ Guardado" con ✏️ Editar y ↩️ Deshacer. */
    Accion.Enviar guardar(Movimiento pendiente, String prefijo) {
        Movimiento m = movimientos.confirmar(pendiente.getId()).movimiento();
        return Accion.Enviar.tarjeta(prefijo + tarjetas.guardadoAutomatico(m), teclado(m), m.getId());
    }

    /** La tarjeta de un gasto que se guardó solo, como está ahora. */
    Accion.Editar tarjeta(int mensajeId, Movimiento m) {
        return new Accion.Editar(mensajeId, tarjetas.guardadoAutomatico(m), teclado(m));
    }

    Teclado teclado(Movimiento m) {
        return tarjetas.guardadoAutomatico(m, catalogos.cargar().mediosPago());
    }

    /** ↩️ Deshacer: el gasto se borra (con sus productos y la foto del ticket), en cualquier momento. */
    List<Accion> deshacer(int mensajeId, long id) {
        Movimiento m = movimientos.borrar(id);
        if (m.getFotoTicket() != null) {
            tickets.borrarArchivoDeFoto(m.getFotoTicket());
        }
        return List.of(new Accion.Editar(mensajeId, tarjetas.deshecho(m), Teclado.NINGUNO),
                new Accion.Avisar("↩️ Deshecho"));
    }

    /** Un toque en la fila rápida: el gasto guardado pasa a tener ese medio de pago. */
    List<Accion> elegirMedio(int mensajeId, long id, long medioPagoId) {
        Movimiento m = movimientos.buscar(id);
        Movimiento editado = movimientos.editar(id, m.getMonto(), m.getDescripcion(), m.getFechaHora(),
                m.getCategoria().getId(), medioPagoId, m.getCuenta() == null ? null : m.getCuenta().getId());
        return List.of(tarjeta(mensajeId, editado), new Accion.Avisar("💳 " + editado.getMedioPago().getNombre()));
    }
}
