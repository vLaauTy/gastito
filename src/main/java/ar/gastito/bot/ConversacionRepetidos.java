package ar.gastito.bot;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.RepetidosService;
import ar.gastito.ticket.TicketService;

/**
 * Compras repetidas en el chat: si un gasto nuevo se parece a uno que ya estaba, en lugar de la tarjeta de siempre
 * van los dos lado a lado con "Es la misma: uní los datos" y "Compré de nuevo: guardá las dos".
 */
@Component
public class ConversacionRepetidos {

    private final RepetidosService repetidos;
    private final MovimientoService movimientos;
    private final TicketService tickets;
    private final Tarjetas tarjetas;
    private final GastoFijoService fijos;
    private final FormatoMensajes formato;

    public ConversacionRepetidos(RepetidosService repetidos, MovimientoService movimientos, TicketService tickets,
            Tarjetas tarjetas, GastoFijoService fijos, FormatoMensajes formato) {
        this.repetidos = repetidos;
        this.movimientos = movimientos;
        this.tickets = tickets;
        this.tarjetas = tarjetas;
        this.fijos = fijos;
        this.formato = formato;
    }

    /** La tarjeta "Ya registrado / Nuevo", si el gasto recién creado se parece a otro; si no, vacío. */
    public Optional<Accion.Enviar> siEsRepetido(Movimiento nuevo) {
        return repetidos.buscar(nuevo).map(existente -> Accion.Enviar.tarjeta(tarjetas.repetido(existente, nuevo),
                tarjetas.repetido(nuevo.getId(), existente.getId()), nuevo.getId()));
    }

    /** Lo mismo, editando un mensaje que ya está (el de "¿es un gasto o una pregunta?"). */
    public Optional<Accion.Editar> siEsRepetido(Movimiento nuevo, int mensajeId) {
        return repetidos.buscar(nuevo).map(existente -> new Accion.Editar(mensajeId,
                tarjetas.repetido(existente, nuevo), tarjetas.repetido(nuevo.getId(), existente.getId())));
    }

    /** "Es la misma": queda uno solo, con lo mejor de los dos. */
    public List<Accion> unir(int mensajeId, long nuevoId, long existenteId) {
        RepetidosService.Union union = repetidos.unir(nuevoId, existenteId);
        if (union.fotoQueSobra() != null) {
            tickets.borrarArchivoDeFoto(union.fotoQueSobra());
        }
        // Era el cobro de un fijo: queda confirmado con lo cargado, y se avisa si aumentó.
        String aumento = union.confirmoCobroDeFijo()
                ? fijos.alConfirmar(union.movimiento().getId())
                        .map(a -> "\n\n" + ConversacionFijos.textoDeAumento(a, formato)).orElse("")
                : "";
        return List.of(new Accion.Editar(mensajeId, tarjetas.unidos(union.movimiento()) + aumento, Teclado.NINGUNO),
                new Accion.Avisar("🔗 Unidos"));
    }

    /**
     * "Compré de nuevo": se guarda el nuevo. Si le falta algo, o es un ticket (que tiene sus propios avisos para
     * revisar), vacío: vuelve la tarjeta de siempre.
     */
    public Optional<List<Accion>> deNuevo(int mensajeId, long nuevoId) {
        Movimiento nuevo = movimientos.buscar(nuevoId);
        if (!nuevo.isCompleto() || nuevo.getOrigen() == Origen.TICKET) {
            return Optional.empty();
        }
        MovimientoService.Confirmacion c = movimientos.confirmar(nuevoId);
        return Optional.of(List.of(new Accion.Editar(mensajeId,
                tarjetas.guardado(c.movimiento(), c.totalMesCategoria()), Teclado.NINGUNO),
                new Accion.Avisar("✅ Guardado")));
    }
}
