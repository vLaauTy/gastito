package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;

/**
 * Varios gastos en un solo mensaje ("Gasté 90.000 en luz, 70000 en gas y 31500 en Netflix"): una tarjeta con todos,
 * cada uno con ✅ (listo para guardar) o ❓ (falta la categoría). Los ❓ quedan como tarjeta aparte para completarlos.
 */
@Component
public class ConversacionVarios {

    static final String GUARDAR_TODO = "vg";
    static final String CANCELAR_TODOS = "vz";
    /** Cuántos grupos sin responder se recuerdan (los más viejos se olvidan). */
    static final int GRUPOS_RECORDADOS = 10;

    private final MovimientoService movimientos;
    private final GastoFijoService fijos;
    private final FormatoMensajes formato;
    private final Tarjetas tarjetas;

    private final AtomicLong siguiente = new AtomicLong();
    private final Map<Long, List<Long>> grupos = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<Long>> mas) {
            return size() > GRUPOS_RECORDADOS;
        }
    });

    public ConversacionVarios(MovimientoService movimientos, GastoFijoService fijos, FormatoMensajes formato,
            Tarjetas tarjetas) {
        this.movimientos = movimientos;
        this.fijos = fijos;
        this.formato = formato;
        this.tarjetas = tarjetas;
    }

    /** La tarjeta combinada de varios gastos recién interpretados (todavía pendientes). */
    public Accion.Enviar tarjeta(List<Movimiento> items, String prefijo) {
        long id = siguiente.incrementAndGet();
        grupos.put(id, items.stream().map(Movimiento::getId).toList());
        return Accion.Enviar.conBotones(prefijo + texto(items), teclado(id));
    }

    private String texto(List<Movimiento> items) {
        StringBuilder sb = new StringBuilder("🧾 <b>" + items.size() + " gastos</b>");
        for (Movimiento m : items) {
            sb.append('\n').append(m.isCompleto() ? "✅ " : "❓ ")
                    .append(html(m.getDescripcion() == null ? "(sin descripción)" : m.getDescripcion()));
            if (m.getMonto() != null) {
                sb.append(" .... ").append(formato.pesos(m.getMonto()));
            }
            if (m.getGastoFijoId() != null) {
                sb.append(" (fijo)");
            }
        }
        for (Movimiento m : items) {
            if (m.getGastoFijoId() == null) {
                continue;
            }
            fijos.yaCargadoEstePeriodo(m.getGastoFijoId(), YearMonth.from(m.getFijoPeriodo())).ifPresent(anterior ->
                    sb.append("\n⚠️ Ya cargaste ").append(html(minuscula(m.getDescripcion()))).append(" este mes (")
                            .append(formato.pesos(anterior.getMonto())).append("). ¿Es otro pago?"));
        }
        if (items.stream().anyMatch(m -> !m.isCompleto())) {
            sb.append("\n\nLos que tienen ❓ los armo en una tarjeta aparte para completarlos.");
        }
        return sb.toString();
    }

    private static String minuscula(String texto) {
        return texto == null || texto.isEmpty() ? "ese gasto"
                : Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
    }

    private static Teclado teclado(long id) {
        return Teclado.constructor()
                .fila(new Teclado.Boton("✅ Guardar todo", GUARDAR_TODO + ":" + id))
                .fila(new Teclado.Boton("❌ Cancelar", CANCELAR_TODOS + ":" + id))
                .build();
    }

    public boolean esBoton(String accion) {
        return GUARDAR_TODO.equals(accion) || CANCELAR_TODOS.equals(accion);
    }

    public List<Accion> boton(int mensajeId, String accion, long id) {
        List<Long> ids = grupos.remove(id);
        if (ids == null) {
            return List.of(new Accion.Avisar("Ese grupo ya no está pendiente"));
        }
        if (CANCELAR_TODOS.equals(accion)) {
            ids.forEach(this::cancelarSiSePuede);
            return List.of(new Accion.Editar(mensajeId, "❌ <b>Cancelado</b>\nNinguno de estos gastos se guardó.",
                    Teclado.NINGUNO));
        }
        int guardados = 0;
        List<Accion> aparte = new ArrayList<>();
        for (Long movimientoId : ids) {
            Movimiento m = movimientos.buscar(movimientoId);
            if (m.isCompleto()) {
                movimientos.confirmar(movimientoId);
                if (m.getGastoFijoId() != null) {
                    fijos.alConfirmar(movimientoId);
                }
                guardados++;
            } else {
                aparte.add(Accion.Enviar.tarjeta(tarjetas.pendiente(m), tarjetas.principal(m), m.getId()));
            }
        }
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.Editar(mensajeId, "✅ <b>" + guardados + " gasto(s) guardado(s)</b>"
                + (aparte.isEmpty() ? "" : "\nLos demás quedaron abajo para completarlos."), Teclado.NINGUNO));
        acciones.addAll(aparte);
        return acciones;
    }

    private void cancelarSiSePuede(long movimientoId) {
        try {
            movimientos.cancelar(movimientoId);
        } catch (RuntimeException e) {
            // ya no estaba pendiente: no hay nada para cancelar
        }
    }
}
