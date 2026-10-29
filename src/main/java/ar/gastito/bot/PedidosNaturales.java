package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.cuota.VistaPlanCuotas;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.ticket.TicketService;

/**
 * Los pedidos dichos en lenguaje natural que cambian algo ya cargado: corregir o borrar lo último, dar de baja un
 * fijo, cancelar una compra en cuotas, dar por perdida una deuda. Nada se cambia sin que el usuario lo confirme con
 * un botón; si no se encuentra a qué se refiere, se muestra la lista para elegir.
 */
@Component
public class PedidosNaturales {

    static final String APLICAR_CORRECCION = "uc";
    static final String BORRAR = "ub";
    static final String BAJA_FIJO = "uf";
    static final String NADA = "un";
    static final int CORRECCIONES_RECORDADAS = 10;

    private final MovimientoService movimientos;
    private final GastoFijoService fijos;
    private final PlanCuotasService planes;
    private final JuntadaService juntadas;
    private final FormatoMensajes formato;
    private final Tarjetas tarjetas;
    private final TicketService tickets;

    private record Pendiente(long movimientoId, ValidadorInterpretacion.Correccion correccion) {
    }

    private final AtomicLong siguiente = new AtomicLong();
    private final Map<Long, Pendiente> correcciones = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Pendiente> mas) {
            return size() > CORRECCIONES_RECORDADAS;
        }
    });

    public PedidosNaturales(MovimientoService movimientos, GastoFijoService fijos, PlanCuotasService planes,
            JuntadaService juntadas, FormatoMensajes formato, Tarjetas tarjetas, TicketService tickets) {
        this.movimientos = movimientos;
        this.fijos = fijos;
        this.planes = planes;
        this.juntadas = juntadas;
        this.formato = formato;
        this.tarjetas = tarjetas;
        this.tickets = tickets;
    }

    // --- Corregir o borrar lo último ---

    /** "El último era 3500, no 3700": muestra el cambio y pregunta antes de aplicarlo. */
    public Accion corregir(ValidadorInterpretacion.Correccion c, String prefijo) {
        Optional<Movimiento> ultimo = movimientos.ultimoCargado();
        if (ultimo.isEmpty()) {
            return Accion.Enviar.texto(prefijo + "🤔 Todavía no cargaste nada para corregir.");
        }
        Movimiento m = ultimo.get();
        if (c.campo() == null) {
            return Accion.Enviar.conBotones(prefijo + "✏️ Lo último que cargaste:\n" + resumen(m)
                    + "\n\n¿Qué querés cambiar? Decime el dato nuevo como te salga (<i>era 3500</i>, "
                    + "<i>fue con débito</i>).", Teclado.constructor()
                            .fila(new Teclado.Boton("↩️ Dejarlo así", NADA + ":0")).build());
        }
        if (c.campo() == ValidadorInterpretacion.Campo.MONTO && (m.isJuntada() || m.getPlanCuotasId() != null)) {
            return Accion.Enviar.conBotones(prefijo + "🤔 Lo último que cargaste es "
                    + (m.isJuntada() ? "una juntada" : "una compra en cuotas") + ":\n" + resumen(m)
                    + "\n\nSu monto mueve otras partes, así que no lo cambio desde acá.", alternativas(m));
        }
        long id = siguiente.incrementAndGet();
        correcciones.put(id, new Pendiente(m.getId(), c));
        return Accion.Enviar.conBotones(prefijo + "✏️ <b>¿Corrijo lo último que cargaste?</b>\n" + resumen(m)
                + "\n" + cambio(m, c), Teclado.constructor()
                        .fila(new Teclado.Boton("✅ Sí, corregir", APLICAR_CORRECCION + ":" + id),
                                new Teclado.Boton("❌ No", NADA + ":0"))
                        .build());
    }

    /** "Borrá lo último que cargué": muestra qué se borraría y pregunta. */
    public Accion borrar(String prefijo) {
        Optional<Movimiento> ultimo = movimientos.ultimoCargado();
        if (ultimo.isEmpty()) {
            return Accion.Enviar.texto(prefijo + "🤔 Todavía no cargaste nada para borrar.");
        }
        Movimiento m = ultimo.get();
        if (m.isJuntada() || m.getPlanCuotasId() != null) {
            return Accion.Enviar.conBotones(prefijo + "🤔 Lo último que cargaste es "
                    + (m.isJuntada() ? "una juntada" : "una compra en cuotas") + ":\n" + resumen(m)
                    + "\n\nEso no se borra así: tiene otras partes que dependen de él.", alternativas(m));
        }
        return Accion.Enviar.conBotones(prefijo + "🗑 <b>¿Borro lo último que cargaste?</b>\n" + resumen(m),
                Teclado.constructor()
                        .fila(new Teclado.Boton("🗑 Sí, borrar", BORRAR + ":" + m.getId()),
                                new Teclado.Boton("❌ No", NADA + ":0"))
                        .build());
    }

    /** Para una juntada, su tarjeta (ahí se da por perdida una parte); para una cuota, cancelar el plan. */
    private static Teclado alternativas(Movimiento m) {
        Teclado.Boton propio = m.isJuntada()
                ? new Teclado.Boton("👥 Ver la juntada", Tarjetas.JUNTADA_VER + ":" + m.getId())
                : new Teclado.Boton("🗑 Cancelar el plan de cuotas",
                        ConversacionCuotas.CANCELAR_PLAN + ":" + m.getPlanCuotasId());
        return Teclado.constructor().fila(propio, new Teclado.Boton("↩️ Dejarlo así", NADA + ":0")).build();
    }

    private String resumen(Movimiento m) {
        return "• " + formato.linea(m) + " · " + html(formato.fecha(m.getFechaHora()))
                + (m.isPendiente() ? " <i>(sin guardar)</i>" : "");
    }

    private String cambio(Movimiento m, ValidadorInterpretacion.Correccion c) {
        return switch (c.campo()) {
            case MONTO -> "💰 " + (m.getMonto() == null ? "" : formato.pesos(m.getMonto()) + " → ") + "<b>"
                    + formato.pesos(c.monto()) + "</b>";
            case DESCRIPCION -> "📝 " + html(m.getDescripcion()) + " → <b>" + html(c.descripcion()) + "</b>";
            case FECHA -> "📅 " + html(formato.fecha(m.getFechaHora())) + " → <b>"
                    + html(formato.fecha(c.fechaHora())) + "</b>";
            case CATEGORIA -> "🏷 " + (m.getCategoria() == null ? "sin categoría"
                    : html(m.getCategoria().getNombreConEmoji())) + " → <b>"
                    + html(c.categoria().getNombreConEmoji()) + "</b>";
            case MEDIO -> "💳 " + html(FormatoMensajes.medio(m.getMedioPago(), m.getCuenta())) + " → <b>"
                    + html(c.medio().getNombre()) + "</b>";
        };
    }

    // --- Dar de baja un fijo ---

    /** "Sacá Netflix de los fijos": si se sabe cuál, se pregunta; si no, la lista con un botón por fijo. */
    public Optional<Accion> bajaDeFijo(String cual, String prefijo) {
        Optional<VistaFijo> fijo = cual == null ? Optional.empty() : fijos.parecido(cual);
        return fijo.map(f -> Accion.Enviar.conBotones(prefijo + "🗑 <b>¿Doy de baja el fijo " + html(f.nombre())
                + "?</b> Deja de aparecer en el checklist; lo que ya pagaste queda.", Teclado.constructor()
                        .fila(new Teclado.Boton("🗑 Sí, dar de baja", BAJA_FIJO + ":" + f.id()),
                                new Teclado.Boton("❌ No", NADA + ":0"))
                        .build()));
    }

    // --- Cancelar una compra en cuotas ---

    /** "Cancelá las cuotas de las zapatillas": si hay una sola que coincide, se pregunta con su botón. */
    public Optional<Accion> cancelarCuotas(String cual, String prefijo) {
        if (cual == null) {
            return Optional.empty();
        }
        List<VistaPlanCuotas> coinciden = planes.activos().stream().filter(p -> coincide(cual, p.descripcion()))
                .toList();
        if (coinciden.size() != 1) {
            return Optional.empty();
        }
        VistaPlanCuotas p = coinciden.get(0);
        return Optional.of(Accion.Enviar.conBotones(prefijo + "🗑 <b>¿Cancelo las cuotas de " + html(p.descripcion())
                + "?</b> Vas por la " + p.cuotasPagadas() + " de " + p.cantidad() + " (" + formato.pesos(p.valorCuota())
                + " c/u). Se borran las cuotas que faltan; las que ya pasaron quedan.", Teclado.constructor()
                        .fila(new Teclado.Boton("🗑 Sí, cancelar", ConversacionCuotas.CANCELAR_PLAN + ":" + p.id()),
                                new Teclado.Boton("❌ No", NADA + ":0"))
                        .build()));
    }

    // --- Dar por perdida una deuda ---

    /**
     * "Lo de Pablo dalo por perdido": si hay una sola parte pendiente de esa persona (en la juntada que dijo, si la
     * dijo), se pregunta con su botón.
     */
    public Optional<Accion> perdonar(String persona, String de, String prefijo) {
        if (persona == null) {
            return Optional.empty();
        }
        record Candidata(VistaJuntada juntada, VistaJuntada.Persona persona) {
        }
        List<Candidata> candidatas = juntadas.conDeudasPendientes().stream()
                .filter(j -> de == null || coincide(de, j.descripcion()))
                .flatMap(j -> j.pendientes().stream().filter(p -> coincide(persona, p.nombre()))
                        .map(p -> new Candidata(j, p)))
                .toList();
        if (candidatas.size() != 1) {
            return Optional.empty();
        }
        Candidata c = candidatas.get(0);
        return Optional.of(Accion.Enviar.conBotones(prefijo + "🚫 <b>¿Doy por perdida la parte de "
                + html(c.persona().nombre()) + "</b> (" + formato.pesos(c.persona().restante()) + ") de "
                + html(c.juntada().descripcion() == null ? "la juntada" : c.juntada().descripcion())
                + "? Pasa a ser gasto tuyo, con la fecha de la juntada.", Teclado.constructor()
                        .fila(new Teclado.Boton("🚫 Sí, dar por perdida", Tarjetas.JUNTADA_PERDIDO + ":"
                                + c.juntada().movimientoId() + ":" + c.persona().id()),
                                new Teclado.Boton("❌ No", NADA + ":0"))
                        .build()));
    }

    // --- Botones ---

    public boolean esBoton(String accion) {
        return List.of(APLICAR_CORRECCION, BORRAR, BAJA_FIJO, NADA).contains(accion);
    }

    public List<Accion> boton(int mensajeId, String accion, long id) {
        return switch (accion) {
            case APLICAR_CORRECCION -> {
                Pendiente p = correcciones.remove(id);
                if (p == null) {
                    yield List.of(new Accion.Editar(mensajeId, "✏️ Esa corrección ya no está pendiente.",
                            Teclado.NINGUNO));
                }
                ValidadorInterpretacion.Correccion c = p.correccion();
                Movimiento m = movimientos.corregir(p.movimientoId(), c.monto(), c.descripcion(), c.fechaHora(),
                        c.categoria() == null ? null : c.categoria().getId(), c.medio() == null ? null
                                : c.medio().getId());
                Movimiento actualizado = movimientos.buscar(m.getId());
                yield List.of(new Accion.Editar(mensajeId, "✅ <b>Corregido</b>\n" + resumen(actualizado),
                        Teclado.NINGUNO), new Accion.Avisar("✅ Corregido"));
            }
            case BORRAR -> {
                Movimiento m = movimientos.anular(id);
                if (m.getOrigen() == Origen.TICKET) {
                    tickets.borrarFoto(m);
                }
                yield List.of(new Accion.Editar(mensajeId, tarjetas.cancelado(movimientos.buscar(m.getId())),
                        Teclado.NINGUNO), new Accion.Avisar("🗑 Borrado"));
            }
            case BAJA_FIJO -> {
                VistaFijo f = fijos.vista(id);
                fijos.darDeBaja(id);
                yield List.of(new Accion.Editar(mensajeId, "🗑 <b>" + html(f.nombre()) + "</b> ya no es un gasto fijo.",
                        Teclado.NINGUNO), new Accion.Avisar("🗑 Listo"));
            }
            default -> List.of(new Accion.Editar(mensajeId, "👌 No cambié nada.", Teclado.NINGUNO));
        };
    }

    /** Si alguna palabra de lo que dijo (de 3 letras o más) aparece en el nombre, sin tildes ni mayúsculas. */
    static boolean coincide(String dicho, String nombre) {
        if (dicho == null || nombre == null) {
            return false;
        }
        String n = " " + normalizar(nombre) + " ";
        return Arrays.stream(normalizar(dicho).split("\\s+")).filter(p -> p.length() >= 3)
                .anyMatch(p -> n.contains(" " + p + " ") || n.contains(" " + p) && p.length() >= 4);
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").strip();
    }
}
