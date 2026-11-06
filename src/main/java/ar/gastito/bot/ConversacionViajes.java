package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import ar.gastito.movimiento.Movimiento;
import ar.gastito.viaje.PanelViaje;
import ar.gastito.viaje.ParserViaje;
import ar.gastito.viaje.ViajeInvalidoException;
import ar.gastito.viaje.ViajeService;

/**
 * Los viajes en el chat: "me voy a Bariloche del 10/1 al 17/1" (se confirma), "para Bariloche me falta el
 * hospedaje, ≈ 400.000" (se anota con Deshacer), "¿cómo va Bariloche?" y "/viajes" (el panel con lo que falta y
 * cuánto ahorrar), los avisos del modo viaje y el resumen final.
 */
@Component
public class ConversacionViajes {

    /** Guardar el viaje nuevo: "wg:<n>". */
    static final String GUARDAR = "wg";
    /** Cancelar el viaje nuevo: "wx:<n>". */
    static final String CANCELAR = "wx";
    /** Deshacer un pendiente recién anotado: "wd:<pendiente>". */
    static final String DESHACER_PENDIENTE = "wd";
    /** Ver el panel del viaje: "wv:<viaje>". */
    static final String VER = "wv";
    /** "✅ Pagué: Hospedaje": la tarjeta del gasto del viaje, para confirmar: "wp:<pendiente>". */
    static final String PAGAR = "wp";
    /** "🧳 No es del viaje": "wn:<movimiento>". */
    static final String NO_ES_DEL_VIAJE = "wn";

    /** Cuántos viajes nuevos se recuerdan esperando Guardar o Cancelar. */
    static final int RECORDADOS = 10;
    static final int BOTONES_DE_PENDIENTES = 6;

    private static final Locale ES = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("d/M");
    private static final DateTimeFormatter DIA_SEMANA = DateTimeFormatter.ofPattern("EEE d/M", ES);

    private final ViajeService viajes;
    private final FormatoMensajes formato;
    private final Clock clock;

    private final AtomicLong siguiente = new AtomicLong();
    private final Map<Long, ParserViaje.Nuevo> nuevos = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ParserViaje.Nuevo> mas) {
            return size() > RECORDADOS;
        }
    });

    public ConversacionViajes(ViajeService viajes, FormatoMensajes formato, Clock clock) {
        this.viajes = viajes;
        this.formato = formato;
        this.clock = clock;
    }

    static boolean esBoton(String accion) {
        return switch (accion) {
            case GUARDAR, CANCELAR, DESHACER_PENDIENTE, VER -> true;
            default -> false;
        };
    }

    // --- Mensajes ---

    /** Un pedido de viajes dicho sin IA; vacío si el mensaje no es de viajes. */
    public Optional<List<Accion>> intentar(String texto, String prefijo) {
        Optional<ParserViaje.Pedido> pedido = ParserViaje.parsear(texto, viajes.lista(), LocalDate.now(clock));
        if (pedido.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(switch (pedido.get()) {
                case ParserViaje.Nuevo n -> List.of(nuevo(n, prefijo));
                case ParserViaje.Falta f -> {
                    viajes.agregarPendiente(f.viaje().getId(), f.descripcion(), f.monto(), null);
                    yield List.of(anotado(viajes.ultimoPendiente(f.viaje().getId()), viajes.panel(f.viaje().getId()),
                            prefijo));
                }
                case ParserViaje.Consulta c -> consulta(c.viaje() == null ? null : c.viaje().getId(), prefijo);
            });
        } catch (ViajeInvalidoException e) {
            return Optional.of(List.of(Accion.Enviar.texto(prefijo + "🤔 " + html(e.getMessage()) + ".")));
        }
    }

    /** "/viajes": el viaje en curso o el próximo. */
    public List<Accion> consulta() {
        return consulta(null, "");
    }

    private List<Accion> consulta(Long viajeId, String prefijo) {
        Optional<PanelViaje> panel = viajeId == null ? viajes.actualOProximo() : Optional.of(viajes.panel(viajeId));
        if (panel.isEmpty()) {
            return List.of(Accion.Enviar.texto(prefijo + "🧳 No tenés viajes cargados. Contame, por ejemplo: "
                    + "\"me voy a Bariloche del 10/1 al 17/1, somos 2\"."));
        }
        return List.of(Accion.Enviar.conBotones(prefijo + panel(panel.get()), teclado(panel.get())));
    }

    private Accion nuevo(ParserViaje.Nuevo n, String prefijo) {
        long id = siguiente.incrementAndGet();
        nuevos.put(id, n);
        StringBuilder sb = new StringBuilder(prefijo + "🧳 <b>Nuevo viaje</b>\n📍 " + html(n.nombre()) + "\n📅 "
                + DIA.format(n.desde()) + " al " + DIA.format(n.hasta()) + " ("
                + (java.time.temporal.ChronoUnit.DAYS.between(n.desde(), n.hasta()) + 1) + " días)");
        if (n.personas() != null) {
            sb.append("\n👥 ").append(n.personas()).append(n.personas() == 1 ? " persona" : " personas");
        }
        if (n.presupuesto() != null) {
            sb.append("\n💰 Presupuesto ").append(formato.pesos(n.presupuesto()));
        }
        sb.append("\n\nEntre esas fechas, lo que cargues queda en el viaje (modo viaje).");
        return Accion.Enviar.conBotones(sb.toString(), Teclado.constructor()
                .fila(new Teclado.Boton("✅ Guardar", GUARDAR + ":" + id),
                        new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id))
                .build());
    }

    private Accion anotado(PanelViaje.Pendiente p, PanelViaje v, String prefijo) {
        String html = prefijo + "📝 Anotado para " + html(v.nombreConEmoji()) + ": <b>" + html(p.descripcion())
                + "</b>" + (p.montoAproximado() == null ? " (sin monto)" : " ≈ " + formato.pesos(p.montoAproximado()))
                + "\n" + lineaTotales(v);
        return Accion.Enviar.conBotones(html, Teclado.constructor()
                .fila(new Teclado.Boton("↩️ Deshacer", DESHACER_PENDIENTE + ":" + p.id()),
                        new Teclado.Boton("🧳 Ver el viaje", VER + ":" + v.id()))
                .build());
    }

    /** "🧳 No es del viaje": el gasto (y su préstamo, si salió de él) queda sin viaje. */
    Movimiento noEsDelViaje(long movimientoId) {
        return viajes.quitarDelViaje(movimientoId);
    }

    /** "✅ Pagué: Hospedaje": el gasto del viaje, pendiente de confirmar. */
    Movimiento pendienteComoGasto(long pendienteId) {
        return viajes.pendienteComoGasto(pendienteId);
    }

    // --- Botones ---

    public List<Accion> boton(int mensajeId, String accion, long id) {
        try {
            return switch (accion) {
                case GUARDAR -> {
                    ParserViaje.Nuevo n = nuevos.remove(id);
                    if (n == null) {
                        yield List.of(new Accion.Avisar("Ese viaje ya no está pendiente"));
                    }
                    PanelViaje v = viajes.crear(new ViajeService.Datos(n.nombre(), null, n.desde(), n.hasta(),
                            n.personas(), n.presupuesto(), null));
                    yield List.of(new Accion.Editar(mensajeId, "✅ <b>Guardado</b>\n" + panel(v), teclado(v)),
                            new Accion.Avisar("✅ Guardado"));
                }
                case CANCELAR -> {
                    nuevos.remove(id);
                    yield List.of(new Accion.Editar(mensajeId, "❌ <b>Cancelado</b>: no guardé el viaje.",
                            Teclado.NINGUNO), new Accion.Avisar("Cancelado"));
                }
                case DESHACER_PENDIENTE -> {
                    PanelViaje v = viajes.borrarPendiente(id);
                    yield List.of(new Accion.Editar(mensajeId, "↩️ <b>Deshecho</b>: no quedó anotado.\n"
                            + lineaTotales(v), Teclado.NINGUNO), new Accion.Avisar("↩️ Deshecho"));
                }
                case VER -> {
                    PanelViaje v = viajes.panel(id);
                    yield List.of(Accion.Enviar.conBotones(panel(v), teclado(v)));
                }
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (ViajeInvalidoException e) {
            return List.of(new Accion.Avisar(e.getMessage()));
        }
    }

    /** Un botón por lo que falta pagar (hasta {@value #BOTONES_DE_PENDIENTES}). */
    Teclado teclado(PanelViaje v) {
        Teclado.Constructor t = Teclado.constructor();
        v.sinPagar().stream().limit(BOTONES_DE_PENDIENTES).forEach(p -> t.fila(new Teclado.Boton("✅ Pagué: "
                + p.descripcion(), PAGAR + ":" + p.id())));
        return t.build();
    }

    // --- Textos ---

    /** "💸 Pagado $A · Falta ≈ $B · Total estimado $C". */
    String lineaTotales(PanelViaje v) {
        return "💸 Pagado <b>" + formato.pesos(v.pagado()) + "</b> · Falta ≈ <b>" + formato.pesos(v.falta())
                + "</b> · Total estimado <b>" + formato.pesos(v.totalEstimado()) + "</b>"
                + (v.sinMonto() == 0 ? "" : "\n<i>" + v.sinMonto() + (v.sinMonto() == 1 ? " pendiente" : " pendientes")
                        + " sin monto, no " + (v.sinMonto() == 1 ? "está" : "están") + " en la cuenta.</i>");
    }

    /** El panel del viaje: totales, presupuesto, lo que falta pagar, lo que me deben y cuánto ahorrar. */
    String panel(PanelViaje v) {
        StringBuilder sb = new StringBuilder(html(v.emoji() == null ? "🧳" : v.emoji()) + " <b>" + html(v.nombre())
                + "</b> · " + DIA.format(v.desde()) + " al " + DIA.format(v.hasta()) + " (" + v.dias() + " días)"
                + (v.personas() == null ? "" : " · 👥 " + v.personas()));
        sb.append("\n").append(lineaTotales(v));
        if (v.presupuesto() != null) {
            sb.append("\n📊 Presupuesto ").append(formato.pesos(v.presupuesto())).append(": ")
                    .append(barra(v.porcentajePresupuesto())).append(' ')
                    .append(v.porcentajePresupuesto().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(" %");
        }
        if (v.estado().equals(PanelViaje.EN_CURSO)) {
            enCurso().ifPresent(l -> sb.append("\n").append(l));
        }
        List<PanelViaje.Pendiente> sinPagar = v.sinPagar();
        if (!sinPagar.isEmpty()) {
            sb.append("\n\n📝 <b>Falta pagar</b>");
            for (PanelViaje.Pendiente p : sinPagar) {
                sb.append("\n• ").append(html(p.descripcion())).append(p.montoAproximado() == null
                        ? " · <i>sin monto</i>" : " ≈ " + formato.pesos(p.montoAproximado()));
            }
        }
        if (v.meDeben().signum() > 0) {
            sb.append("\n\n🤝 Te deben <b>").append(formato.pesos(v.meDeben()))
                    .append("</b> por el viaje (préstamos y juntadas).");
        }
        if (v.ahorro() != null) {
            sb.append("\n\n").append(ahorro(v));
        }
        return sb.toString();
    }

    /**
     * "Para Bariloche falta pagar ≈ $X (sin contar lo que no tiene monto). Hasta el inicio quedan N cobros de
     * sueldo: separá ≈ $Y por mes."
     */
    String ahorro(PanelViaje v) {
        var a = v.ahorro();
        StringBuilder sb = new StringBuilder("🐷 Para " + html(v.nombre()) + " falta pagar ≈ <b>"
                + formato.pesos(a.falta()) + "</b>" + (v.sinMonto() > 0 ? " (sin contar lo que no tiene monto)" : "")
                + ".");
        if (a.reservado().signum() > 0) {
            sb.append(" Tenés ").append(formato.pesos(a.reservado())).append(" reservados en inversiones");
            sb.append(a.aSeparar().signum() == 0 ? ": alcanza 🎉" : ", así que faltan ≈ " + formato.pesos(a.aSeparar())
                    + ".");
        }
        if (a.aSeparar().signum() > 0) {
            if (a.cobros() == 0) {
                sb.append(" No quedan cobros de sueldo antes del viaje.");
            } else {
                sb.append(" Hasta el inicio ").append(a.cobros() == 1 ? "queda 1 cobro" : "quedan " + a.cobros()
                        + " cobros").append(" de sueldo: separá ≈ <b>").append(formato.pesos(a.porMes()))
                        .append("</b> por mes.");
            }
        }
        return sb.toString();
    }

    private static String barra(BigDecimal porcentaje) {
        int llenos = Math.min(10, porcentaje.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).intValue());
        return "▓".repeat(llenos) + "░".repeat(10 - llenos) + (porcentaje.compareTo(BigDecimal.valueOf(100)) > 0
                ? " ⚠️" : "");
    }

    /** "🏔 Bariloche: llevás $X en N días (≈ $Y por día)", mientras dura el viaje. */
    public Optional<String> enCurso() {
        return viajes.enCurso().map(e -> html(e.nombreConEmoji()) + ": llevás <b>" + formato.pesos(e.gastado())
                + "</b> en " + e.dias() + (e.dias() == 1 ? " día" : " días") + " (≈ " + formato.pesos(e.porDia())
                + " por día)");
    }

    // --- Avisos ---

    Accion.Enviar inicio(PanelViaje v) {
        return Accion.Enviar.conBotones("🧳 <b>Arrancó el modo viaje: " + html(v.nombreConEmoji()) + "</b>\nHasta el "
                + DIA.format(v.hasta()) + ", todo lo que cargues queda en el viaje. Si algo no es, tocá «No es del "
                + "viaje» en su tarjeta. ¡Buen viaje!", teclado(v));
    }

    Accion.Enviar fin(PanelViaje v) {
        return Accion.Enviar.texto("🏁 Hoy es el último día del modo viaje: " + html(v.nombreConEmoji())
                + ". Mañana te mando el resumen.\n" + lineaTotales(v));
    }

    /** Después del viaje: total, por categoría, por día, por persona y lo que falta cobrar. */
    Accion.Enviar resumenFinal(PanelViaje v) {
        StringBuilder sb = new StringBuilder("🏁 <b>Resumen de " + html(v.nombreConEmoji()) + "</b> ("
                + DIA.format(v.desde()) + " al " + DIA.format(v.hasta()) + ", " + v.dias() + " días)");
        sb.append("\n💸 Te costó <b>").append(formato.pesos(v.pagado())).append("</b>");
        if (v.porLosDemas().signum() > 0) {
            sb.append("\n🤝 Además pusiste ").append(formato.pesos(v.porLosDemas())).append(" por los demás");
        }
        if (v.personas() != null && v.personas() > 1) {
            BigDecimal total = v.pagado().add(v.porLosDemas());
            sb.append("\n👥 Entre ").append(v.personas()).append(": ≈ <b>")
                    .append(formato.pesos(total.divide(BigDecimal.valueOf(v.personas()), 0, RoundingMode.HALF_UP)))
                    .append("</b> por persona");
        }
        if (v.presupuesto() != null) {
            sb.append("\n📊 Presupuesto ").append(formato.pesos(v.presupuesto())).append(": ")
                    .append(v.porcentajePresupuesto().setScale(0, RoundingMode.HALF_UP).toPlainString())
                    .append(" % usado");
        }
        if (!v.porCategoria().isEmpty()) {
            sb.append("\n\n<b>Por categoría</b>");
            for (PanelViaje.PorCategoria c : v.porCategoria()) {
                sb.append("\n• ").append(c.emoji() == null ? "" : c.emoji() + " ").append(html(c.categoria()))
                        .append(": ").append(formato.pesos(c.total()));
                if (c.porcentaje() != null) {
                    sb.append(" (").append(c.porcentaje().setScale(0, RoundingMode.HALF_UP).toPlainString())
                            .append(" %)");
                }
            }
        }
        List<String> dias = new ArrayList<>();
        BigDecimal antes = BigDecimal.ZERO;
        for (PanelViaje.PorDia d : v.porDia()) {
            if (d.dia().isBefore(v.desde())) {
                antes = antes.add(d.total());
            } else if (!d.dia().isAfter(v.hasta())) {
                dias.add("• " + DIA_SEMANA.format(d.dia()) + ": " + formato.pesos(d.total()));
            }
        }
        if (!dias.isEmpty()) {
            sb.append("\n\n<b>Por día</b>");
            if (antes.signum() > 0) {
                sb.append("\n• Antes del viaje: ").append(formato.pesos(antes));
            }
            dias.forEach(d -> sb.append("\n").append(d));
        }
        sb.append(v.meDeben().signum() > 0 ? "\n\n📥 Te falta cobrar <b>" + formato.pesos(v.meDeben())
                + "</b> (préstamos y juntadas del viaje)." : "\n\n📥 Nadie te debe nada del viaje.");
        return Accion.Enviar.texto(sb.toString());
    }
}
