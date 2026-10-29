package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.PedidoJuntada;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.juntada.JuntadaInvalidaException;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * Las juntadas en el chat: la tarjeta con Pagaste / Tu gasto real / A cobrar y las personas, los nombres, la parte
 * de alguien, el mensaje para el grupo, dar una parte por perdida y las devoluciones ("Pablo me pasó 8000").
 */
@Component
public class ConversacionJuntadas {

    /** Cuántas juntadas se ofrecen como mucho al elegir de cuál es una devolución. */
    static final int MAXIMO_OPCIONES = 8;

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE d/M", Locale.forLanguageTag("es-AR"));

    private final JuntadaService juntadas;
    private final MovimientoService movimientos;
    private final Tarjetas tarjetas;
    private final FormatoMensajes formato;

    /** Qué se espera que el usuario escriba: el nombre o la parte de alguien. */
    enum Pedido {
        NOMBRE, PARTE
    }

    record Espera(Pedido pedido, long movimientoId, long participanteId, int mensajeId) {
    }

    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();

    public ConversacionJuntadas(JuntadaService juntadas, MovimientoService movimientos, Tarjetas tarjetas,
            FormatoMensajes formato) {
        this.juntadas = juntadas;
        this.movimientos = movimientos;
        this.tarjetas = tarjetas;
        this.formato = formato;
    }

    // --- Crear y guardar ---

    /** El gasto recién creado es una juntada: su monto (el total) se reparte y queda mi parte. */
    public Movimiento crear(Movimiento m, PedidoJuntada pedido) {
        juntadas.crear(m.getId(), pedido.personas(), pedido.nombres());
        return movimientos.buscar(m.getId());
    }

    public boolean esJuntada(long movimientoId) {
        return juntadas.vista(movimientoId).isPresent();
    }

    /** Se cambió el monto en la tarjeta: en una juntada es el total pagado, que se reparte de nuevo. */
    public Movimiento cambiarTotal(long movimientoId, BigDecimal total) {
        juntadas.cambiarTotal(movimientoId, total);
        return movimientos.buscar(movimientoId);
    }

    /** Se guardó una juntada: queda anotado desde dónde salió el total, y la tarjeta ofrece lo que sigue. */
    public List<Accion> guardada(int mensajeId, MovimientoService.Confirmacion c) {
        juntadas.alGuardar(c.movimiento().getId());
        VistaJuntada j = juntadas.vista(c.movimiento().getId()).orElseThrow();
        Movimiento m = movimientos.buscar(c.movimiento().getId());
        return List.of(new Accion.Editar(mensajeId, tarjetas.guardado(m, c.totalMesCategoria()),
                tarjetas.juntadaGuardada(j)), new Accion.Avisar("✅ Guardado"));
    }

    // --- Botones ---

    public List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido) {
        try {
            return switch (accion) {
                case Tarjetas.JUNTADA_VER -> List.of(tarjeta(mensajeId, id));
                case Tarjetas.JUNTADA_MENSAJE -> List.of(Accion.Enviar.texto(mensajeParaElGrupo(vista(id))),
                        new Accion.Avisar("💬 Listo para copiar"));
                case Tarjetas.JUNTADA_NOMBRES, Tarjetas.JUNTADA_PARTE, Tarjetas.JUNTADA_PERDIDO ->
                        elegido == null ? List.of(elegir(mensajeId, accion, id))
                                : elegida(chatId, mensajeId, accion, id, elegido);
                case Tarjetas.DEVOLUCION_DE -> devolucionElegida(mensajeId, id, requerido(elegido));
                case Tarjetas.DEVOLUCION_QUEDA_DEBIENDO -> cobrar(mensajeId, id, requerido(elegido), false);
                case Tarjetas.DEVOLUCION_SALDADO -> cobrar(mensajeId, id, requerido(elegido), true);
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (JuntadaInvalidaException e) {
            return List.of(tarjeta(mensajeId, id), new Accion.Avisar(e.getMessage()));
        }
    }

    private Accion.Editar elegir(int mensajeId, String accion, long id) {
        String pedido = switch (accion) {
            case Tarjetas.JUNTADA_NOMBRES -> "👥 ¿A quién le cambiás el nombre?";
            case Tarjetas.JUNTADA_PARTE -> "💲 ¿La parte de quién cambia?";
            default -> "🚫 ¿Qué parte das por perdida? Pasa a ser gasto tuyo, con la fecha de la juntada.";
        };
        return new Accion.Editar(mensajeId, tarjetas.juntadaPidiendo(movimientos.buscar(id), pedido),
                tarjetas.elegirPersona(accion, vista(id)));
    }

    private List<Accion> elegida(long chatId, int mensajeId, String accion, long id, long participanteId) {
        VistaJuntada.Persona p = persona(vista(id), participanteId);
        return switch (accion) {
            case Tarjetas.JUNTADA_NOMBRES -> pedir(chatId, mensajeId, Pedido.NOMBRE, id, p,
                    "✏️ Escribime el nombre de <b>" + html(p.nombre()) + "</b>.");
            case Tarjetas.JUNTADA_PARTE -> pedir(chatId, mensajeId, Pedido.PARTE, id, p,
                    "✏️ ¿Cuánto pone <b>" + html(p.nombre()) + "</b>? Escribime el monto: las demás partes se "
                            + "acomodan para que el total siga cerrando.");
            default -> {
                juntadas.darPorPerdido(participanteId);
                yield List.of(tarjeta(mensajeId, id), new Accion.Avisar("🚫 La parte de " + p.nombre()
                        + " pasó a ser gasto tuyo"));
            }
        };
    }

    private List<Accion> pedir(long chatId, int mensajeId, Pedido pedido, long id, VistaJuntada.Persona p,
            String texto) {
        esperas.put(chatId, new Espera(pedido, id, p.id(), mensajeId));
        return List.of(new Accion.Editar(mensajeId, tarjetas.juntadaPidiendo(movimientos.buscar(id), texto),
                Teclado.constructor().fila(new Teclado.Boton("↩️ Volver", Tarjetas.JUNTADA_VER + ":" + id)).build()));
    }

    // --- Lo que escribe el usuario ---

    public boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    /** Cualquier otro botón o comando: lo que se esperaba ya no. */
    public void olvidar(long chatId) {
        esperas.remove(chatId);
    }

    /**
     * El nombre o el monto que se pidió. Si no sirve, se dice por qué y se sigue esperando.
     */
    public List<Accion> texto(long chatId, String texto) {
        Espera e = esperas.get(chatId);
        try {
            switch (e.pedido()) {
                case NOMBRE -> juntadas.renombrar(e.participanteId(), texto);
                case PARTE -> {
                    Optional<BigDecimal> monto = Montos.parsear(texto.replaceAll("(?i)\\s*pesos$", "").strip());
                    if (monto.isEmpty()) {
                        return List.of(new Accion.Avisar("No entendí el monto 🤔 ¿Cuánto es?"));
                    }
                    juntadas.cambiarParte(e.participanteId(), monto.get());
                }
            }
        } catch (JuntadaInvalidaException ex) {
            return List.of(Accion.Enviar.texto("🤔 " + html(ex.getMessage()) + ". Probá con otro."));
        }
        esperas.remove(chatId);
        return List.of(tarjeta(e.mensajeId(), e.movimientoId()));
    }

    // --- La tarjeta ---

    private Accion.Editar tarjeta(int mensajeId, long movimientoId) {
        Movimiento m = movimientos.buscar(movimientoId);
        return new Accion.Editar(mensajeId, tarjetas.terminadoOPendiente(m),
                m.isPendiente() ? tarjetas.principal(m) : tarjetas.juntadaGuardada(vista(movimientoId)));
    }

    /** "/juntadas": las que tienen plata a cobrar, cada una con su tarjeta. */
    public List<Accion> abiertas() {
        List<VistaJuntada> abiertas = juntadas.conDeudasPendientes();
        if (abiertas.isEmpty()) {
            return List.of(Accion.Enviar.texto("👥 No tenés juntadas con plata a cobrar."));
        }
        BigDecimal total = abiertas.stream().map(VistaJuntada::aCobrar).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Accion> acciones = new ArrayList<>();
        acciones.add(Accion.Enviar.texto("👥 Te deben <b>" + formato.pesos(total) + "</b> en " + abiertas.size()
                + (abiertas.size() == 1 ? " juntada:" : " juntadas:")));
        for (VistaJuntada j : abiertas) {
            Movimiento m = movimientos.buscar(j.movimientoId());
            acciones.add(Accion.Enviar.conBotones(tarjetas.terminadoOPendiente(m), tarjetas.juntadaGuardada(j)));
        }
        return acciones;
    }

    /** Un texto para copiar y mandar al grupo, con quién debe cuánto. El bot no les escribe a otras personas. */
    String mensajeParaElGrupo(VistaJuntada j) {
        String que = j.descripcion() == null ? "la juntada" : j.descripcion().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("Hola! Les paso lo de " + que + " del " + DIA.format(j.fechaHora())
                + ": fueron " + formato.pesos(j.totalPagado()) + " entre " + j.personas());
        sb.append(j.partesIguales() && !j.participantes().isEmpty()
                ? ", " + formato.pesos(j.participantes().get(0).monto()) + " cada uno.\n" : ".\n");
        // Lo que le falta a cada uno: si alguien ya pagó una parte, solo la diferencia.
        for (VistaJuntada.Persona p : j.pendientes()) {
            sb.append("\n• ").append(p.nombre()).append(": ").append(formato.pesos(p.restante()));
        }
        sb.append("\n\n").append(j.billetera() == null ? "Me lo pueden pasar cuando puedan."
                : "Me lo pueden pasar a " + j.billetera() + ".").append(" ¡Gracias!");
        return "💬 Copiá esto y mandalo al grupo:\n\n<pre>" + html(sb.toString()) + "</pre>";
    }

    // --- Devoluciones ---

    /**
     * "Pablo me pasó 8000": si se sabe de quién es, la tarjeta para guardarla; si hay varias juntadas posibles, se
     * pregunta de cuál con botones. Si no hay juntadas con plata a cobrar, no se guarda nada.
     */
    public Accion devolucion(ValidadorInterpretacion.Devolucion d, Origen origen, String texto, String prefijo) {
        if (d.monto() == null) {
            return Accion.Enviar.texto(prefijo + "↩️ ¿Cuánto te devolvi" + (d.persona() == null ? "eron?"
                    : "ó " + html(d.persona()) + "?") + " Contámelo con el monto y lo anoto.");
        }
        List<JuntadaService.Deudor> deudores = juntadas.deudores(d.persona(), d.de(), d.monto());
        if (deudores.isEmpty()) {
            return Accion.Enviar.texto(prefijo + "↩️ Te pasaron " + formato.pesos(d.monto()) + ", pero no tenés "
                    + "juntadas con plata a cobrar, así que no lo guardé.");
        }
        Movimiento devolucion = juntadas.devolucionPendiente(d.monto(), d.cuenta(), d.medio(), d.fechaHora(), origen,
                texto);
        long id = devolucion.getId();
        StringBuilder html = new StringBuilder(prefijo + "↩️ <b>Te devolvieron plata</b>\n💰 <b>"
                + formato.pesos(d.monto()) + "</b>" + (d.persona() == null ? "" : " · " + html(d.persona())));
        Teclado.Constructor t = Teclado.constructor();
        if (deudores.size() == 1) {
            JuntadaService.Deudor uno = deudores.get(0);
            html.append("\n👥 ").append(deQuien(uno));
            t.fila(new Teclado.Boton("✅ Guardar", Tarjetas.DEVOLUCION_DE + ":" + id + ":" + uno.participanteId()),
                    new Teclado.Boton("❌ Cancelar", Tarjetas.CANCELAR + ":" + id));
        } else {
            html.append("\n\n¿De quién es?");
            deudores.stream().limit(MAXIMO_OPCIONES).forEach(deudor -> t.fila(new Teclado.Boton(
                    deudor.nombre() + " · " + deudor.juntada().descripcion() + " · " + formato.pesos(deudor.monto()),
                    Tarjetas.DEVOLUCION_DE + ":" + id + ":" + deudor.participanteId())));
            t.fila(new Teclado.Boton("❌ Cancelar", Tarjetas.CANCELAR + ":" + id));
        }
        return Accion.Enviar.tarjeta(html.toString(), t.build(), id);
    }

    private String deQuien(JuntadaService.Deudor d) {
        return html(d.nombre()) + ", de " + html(d.juntada().descripcion()) + " del "
                + DIA.format(d.juntada().fechaHora()) + " (te debía " + formato.pesos(d.monto()) + ")";
    }

    /**
     * Se eligió de quién es la devolución. Si paga lo que debía, o más, se guarda; si paga menos, se pregunta si queda
     * debiendo la diferencia o si está saldado.
     */
    private List<Accion> devolucionElegida(int mensajeId, long devolucionId, long participanteId) {
        BigDecimal faltante = juntadas.faltante(devolucionId, participanteId);
        if (faltante.signum() == 0) {
            return cobrar(mensajeId, devolucionId, participanteId, false);
        }
        Movimiento devolucion = movimientos.buscar(devolucionId);
        String datos = ":" + devolucionId + ":" + participanteId;
        return List.of(new Accion.Editar(mensajeId, "↩️ Te pasaron <b>" + formato.pesos(devolucion.getMonto())
                + "</b>, pero te debía " + formato.pesos(devolucion.getMonto().add(faltante)) + ".\n\n¿Cómo queda?",
                Teclado.constructor()
                        .fila(new Teclado.Boton("⏳ Queda debiendo " + formato.pesos(faltante),
                                Tarjetas.DEVOLUCION_QUEDA_DEBIENDO + datos))
                        .fila(new Teclado.Boton("✅ Está saldado", Tarjetas.DEVOLUCION_SALDADO + datos))
                        .fila(new Teclado.Boton("❌ Cancelar", Tarjetas.CANCELAR + ":" + devolucionId))
                        .build()));
    }

    /** @param saldado si pagó menos, la diferencia no se reclama */
    private List<Accion> cobrar(int mensajeId, long devolucionId, long participanteId, boolean saldado) {
        JuntadaService.Cobro cobro = juntadas.cobrar(devolucionId, participanteId, saldado);
        VistaJuntada j = cobro.juntada();
        BigDecimal pago = cobro.devolucion().getMonto();
        StringBuilder html = new StringBuilder("✅ <b>Cobrado</b>\n" + html(cobro.devolucion().getDescripcion())
                + ": " + formato.pesos(pago));
        int comparacion = pago.compareTo(cobro.debia());
        if (comparacion > 0) {
            html.append(" (te debía ").append(formato.pesos(cobro.debia())).append(": te pasó ")
                    .append(formato.pesos(pago.subtract(cobro.debia()))).append(" de más)");
        } else if (cobro.sigueDebiendo().signum() > 0) {
            html.append("\n⏳ Te sigue debiendo <b>").append(formato.pesos(cobro.sigueDebiendo())).append("</b>.");
        } else if (comparacion < 0) {
            html.append("\n✅ Saldado: los ").append(formato.pesos(cobro.debia().subtract(pago)))
                    .append(" que faltaban no se reclaman.");
        }
        html.append(j.aCobrar().signum() > 0
                ? "\n📥 De " + html(j.descripcion()) + " te falta cobrar <b>" + formato.pesos(j.aCobrar()) + "</b>."
                : "\n🎉 De " + html(j.descripcion()) + " ya no te deben nada.");
        html.append("\n\n<i>Es una devolución: no cuenta como ingreso.</i>");
        return List.of(new Accion.Editar(mensajeId, html.toString(), Teclado.NINGUNO),
                new Accion.Avisar("✅ Cobrado"));
    }

    // --- Datos ---

    private VistaJuntada vista(long movimientoId) {
        return juntadas.vista(movimientoId)
                .orElseThrow(() -> new JuntadaInvalidaException("Ese gasto no es una juntada"));
    }

    private static VistaJuntada.Persona persona(VistaJuntada j, long participanteId) {
        return j.participantes().stream().filter(p -> p.id() == participanteId).findFirst()
                .orElseThrow(() -> new JuntadaInvalidaException("Esa persona no está en la juntada"));
    }

    private static long requerido(Long valor) {
        if (valor == null) {
            throw new JuntadaInvalidaException("Botón inválido");
        }
        return valor;
    }
}
