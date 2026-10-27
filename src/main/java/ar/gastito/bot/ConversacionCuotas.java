package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.util.List;

import org.springframework.stereotype.Component;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.cuota.VistaPlanCuotas;
import ar.gastito.interpretacion.PedidoCuotas;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;

/**
 * Las compras en cuotas en el chat: la cuota 1 usa la tarjeta normal de un gasto; al guardarla se generan, ya
 * confirmadas, las que siguen. "/cuotas" lista los planes activos y permite cancelarlos.
 */
@Component
public class ConversacionCuotas {

    static final String CANCELAR_PLAN = "cup";

    private final PlanCuotasService planes;
    private final MovimientoService movimientos;
    private final Tarjetas tarjetas;
    private final FormatoMensajes formato;

    public ConversacionCuotas(PlanCuotasService planes, MovimientoService movimientos, Tarjetas tarjetas,
            FormatoMensajes formato) {
        this.planes = planes;
        this.movimientos = movimientos;
        this.tarjetas = tarjetas;
        this.formato = formato;
    }

    // --- Crear y guardar ---

    /** El gasto recién creado es la cuota 1 de un plan: su monto (el total) queda en el valor de cada cuota. */
    public Movimiento crear(Movimiento m, PedidoCuotas pedido) {
        planes.crear(m.getId(), pedido.cantidad(), pedido.total());
        return movimientos.buscar(m.getId());
    }

    /** Se guardó la cuota 1: se generan, ya confirmadas, las que siguen. */
    public List<Accion> guardada(int mensajeId, MovimientoService.Confirmacion c) {
        List<Movimiento> siguientes = planes.alGuardar(c.movimiento().getId());
        String html = tarjetas.guardado(c.movimiento(), c.totalMesCategoria())
                + (siguientes.isEmpty() ? "" : "\n🗂 Plan de " + c.movimiento().getCuotaTotal() + " cuotas. Próxima: "
                        + formato.fecha(siguientes.get(0).getFechaHora()));
        return List.of(new Accion.Editar(mensajeId, html, Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
    }

    // --- Botones y lista ---

    public boolean esBoton(String accion) {
        return CANCELAR_PLAN.equals(accion);
    }

    public List<Accion> boton(int mensajeId, long id) {
        VistaPlanCuotas p = planes.cancelar(id);
        return List.of(new Accion.Editar(mensajeId, textoDeLista(planes.activos()), tecladoDeLista(planes.activos())),
                new Accion.Avisar("🗑 Plan de " + html(p.descripcion()) + " cancelado"));
    }

    /** "/cuotas": los planes activos con su progreso, y un botón para cancelar cada uno. */
    public List<Accion> lista() {
        List<VistaPlanCuotas> activos = planes.activos();
        return List.of(Accion.Enviar.conBotones(textoDeLista(activos), tecladoDeLista(activos)));
    }

    private String textoDeLista(List<VistaPlanCuotas> activos) {
        if (activos.isEmpty()) {
            return "🗂 No tenés compras en cuotas.";
        }
        StringBuilder sb = new StringBuilder("🗂 <b>Tus compras en cuotas</b>");
        for (VistaPlanCuotas p : activos) {
            sb.append("\n• ").append(p.emoji() == null ? "" : p.emoji() + " ").append(html(p.descripcion()))
                    .append(": cuota ").append(p.cuotasPagadas()).append(" de ").append(p.cantidad())
                    .append(" (").append(formato.pesos(p.valorCuota())).append(" c/u, total ")
                    .append(formato.pesos(p.total())).append(')')
                    .append(p.proximaCuota() == null ? " · terminó"
                            : " · próxima " + formato.fecha(p.proximaCuota().atStartOfDay()));
        }
        return sb.toString();
    }

    private static Teclado tecladoDeLista(List<VistaPlanCuotas> activos) {
        Teclado.Constructor t = Teclado.constructor();
        activos.forEach(p -> t.fila(new Teclado.Boton("🗑 Cancelar " + p.descripcion(), CANCELAR_PLAN + ":" + p.id())));
        return t.build();
    }
}
