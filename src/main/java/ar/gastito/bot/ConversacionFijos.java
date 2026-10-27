package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.RecordatorioFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ParserFijo;
import ar.gastito.interpretacion.PedidoFijo;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;

/**
 * Los gastos fijos en el chat: el alta ("/fijo seguro 32900 tarjeta día 4" o "agregá un fijo: …") con su tarjeta
 * para confirmar, y "/fijos" con la lista y la baja.
 */
@Component
public class ConversacionFijos {

    /** Botones del alta: guardar, cancelar y elegir la categoría; y la baja desde el ABM. */
    static final String GUARDAR = "fa";
    static final String CANCELAR = "fz";
    static final String CATEGORIA = "fk";
    static final String BAJA = "fb";
    /** "Este mes no" desde el checklist de /fijos (todavía no se generó el cobro automático). */
    static final String ESTE_MES_NO_CHECKLIST = "fe";
    /** Cuántas altas sin responder se recuerdan (las más viejas se olvidan). */
    static final int ALTAS_RECORDADAS = 10;

    static final String COMO = "Decímelo así: <code>/fijo seguro 32900 tarjeta día 4</code> (o \"bimestral\", o "
            + "\"variable\" si el monto cambia cada mes).";

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE d/M", Locale.forLanguageTag("es-AR"));

    private final GastoFijoService fijos;
    private final ParserFijo parser;
    private final CatalogoService catalogos;
    private final MovimientoService movimientos;
    private final FormatoMensajes formato;
    private final Clock clock;

    private final AtomicLong siguiente = new AtomicLong();
    private final Map<Long, PedidoFijo> altas = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PedidoFijo> mas) {
            return size() > ALTAS_RECORDADAS;
        }
    });

    public ConversacionFijos(GastoFijoService fijos, ParserFijo parser, CatalogoService catalogos,
            MovimientoService movimientos, FormatoMensajes formato, Clock clock) {
        this.fijos = fijos;
        this.parser = parser;
        this.catalogos = catalogos;
        this.movimientos = movimientos;
        this.formato = formato;
        this.clock = clock;
    }

    // --- Alta ---

    /** "/fijo seguro 32900 tarjeta día 4" (sin IA). */
    public Accion alta(String argumentos) {
        return parser.parsear(argumentos, catalogos.cargar())
                .map(p -> tarjetaDeAlta(p, ""))
                .orElseGet(() -> Accion.Enviar.texto("📌 Para agregar un gasto fijo necesito el nombre, el monto y "
                        + "el día del cobro. " + COMO));
    }

    /** "Agregá un fijo: el seguro, 32900 con tarjeta el 4" (lo entendió la IA). */
    public Accion tarjetaDeAlta(PedidoFijo pedido, String prefijo) {
        if (pedido.monto() == null || pedido.diaDelMes() == null) {
            return Accion.Enviar.texto(prefijo + "📌 " + (pedido.monto() == null ? "¿Cuánto se paga?"
                    : "¿Qué día del mes se cobra?") + " " + COMO);
        }
        long id = siguiente.incrementAndGet();
        altas.put(id, pedido);
        return Accion.Enviar.conBotones(prefijo + textoDeAlta(pedido), teclado(id, pedido));
    }

    String textoDeAlta(PedidoFijo p) {
        StringBuilder sb = new StringBuilder("📌 <b>Nuevo gasto fijo</b>\n💰 <b>" + formato.pesos(p.monto())
                + "</b> · " + (p.bimestral() ? "cada dos meses" : "todos los meses") + ", el día " + p.diaDelMes()
                + "\n📝 " + html(p.nombre()));
        sb.append("\n").append(p.categoria() == null ? "🏷 <b>¿Qué categoría?</b>"
                : html(p.categoria().getNombreConEmoji()));
        sb.append("\n💳 ").append(html(p.tarjeta() != null ? p.tarjeta().getNombre()
                : FormatoMensajes.medio(p.medioPago(), p.cuenta())));
        LocalDate primero = fijos.primerCobro(p.diaDelMes());
        sb.append("\n📅 Primer cobro: ").append(DIA.format(primero));
        if (p.variable()) {
            sb.append("\n📈 Monto variable: cada mes te propongo el promedio de los últimos 3 cobros.");
        }
        return sb.toString();
    }

    private Teclado teclado(long id, PedidoFijo p) {
        Teclado.Constructor t = Teclado.constructor();
        if (p.categoria() == null) {
            List<Categoria> opciones = p.candidatas().isEmpty() ? catalogos.cargar().categorias() : p.candidatas();
            for (int i = 0; i < opciones.size(); i += 2) {
                Categoria a = opciones.get(i);
                if (i + 1 < opciones.size()) {
                    Categoria b = opciones.get(i + 1);
                    t.fila(boton(a.getNombreConEmoji(), CATEGORIA, id, a.getId()),
                            boton(b.getNombreConEmoji(), CATEGORIA, id, b.getId()));
                } else {
                    t.fila(boton(a.getNombreConEmoji(), CATEGORIA, id, a.getId()));
                }
            }
            t.fila(new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id));
        } else {
            t.fila(new Teclado.Boton("✅ Guardar", GUARDAR + ":" + id),
                    new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id));
        }
        return t.build();
    }

    private static Teclado.Boton boton(String texto, String accion, long id, long elegido) {
        return new Teclado.Boton(texto, accion + ":" + id + ":" + elegido);
    }

    // --- Botones ---

    public boolean esBoton(String accion) {
        return GUARDAR.equals(accion) || CANCELAR.equals(accion) || CATEGORIA.equals(accion) || BAJA.equals(accion)
                || ESTE_MES_NO_CHECKLIST.equals(accion);
    }

    public List<Accion> boton(int mensajeId, String accion, long id, Long elegido) {
        if (BAJA.equals(accion)) {
            VistaFijo f = fijos.vista(id);
            fijos.darDeBaja(id);
            return List.of(new Accion.Avisar("🗑 " + f.nombre() + " ya no se cobra"));
        }
        if (ESTE_MES_NO_CHECKLIST.equals(accion)) {
            YearMonth mes = YearMonth.now(clock);
            fijos.esteMesNoDelPeriodo(id, mes);
            List<GastoFijoService.ItemChecklist> items = fijos.checklist(mes);
            return List.of(new Accion.Editar(mensajeId, textoDeChecklist(mes, items), tecladoDeChecklist(items)),
                    new Accion.Avisar("⏭ Listo"));
        }
        PedidoFijo p = altas.get(id);
        if (p == null) {
            return List.of(new Accion.Editar(mensajeId, "📌 Esa alta ya no está pendiente. " + COMO, Teclado.NINGUNO));
        }
        return switch (accion) {
            case CATEGORIA -> {
                Categoria elegida = catalogos.cargar().categorias().stream()
                        .filter(c -> elegido != null && c.getId() == elegido.longValue()).findFirst().orElse(null);
                if (elegida == null) {
                    yield List.of(new Accion.Avisar("Esa categoría no existe"));
                }
                PedidoFijo con = p.conCategoria(elegida);
                altas.put(id, con);
                yield List.of(new Accion.Editar(mensajeId, textoDeAlta(con), teclado(id, con)));
            }
            case CANCELAR -> {
                altas.remove(id);
                yield List.of(new Accion.Editar(mensajeId, "❌ <b>Cancelado</b>\n" + html(p.nombre())
                        + ": no se agregó como gasto fijo.", Teclado.NINGUNO));
            }
            default -> {
                altas.remove(id);
                VistaFijo f = fijos.alta(new GastoFijoService.NuevoFijo(p.nombre(), p.monto(), p.categoria(),
                        p.medioPago(), p.cuenta(), p.tarjeta(), p.diaDelMes(),
                        p.bimestral() ? Frecuencia.BIMESTRAL : Frecuencia.MENSUAL, p.variable()));
                yield List.of(new Accion.Editar(mensajeId, "✅ <b>Gasto fijo guardado</b>\n" + html(f.nombre()) + ": "
                        + formato.pesos(f.montoEsperado()) + (f.frecuencia() == Frecuencia.BIMESTRAL
                                ? " cada dos meses" : " por mes")
                        + ", el día " + f.diaDelMes() + ".\n📅 Primer cobro: " + DIA.format(f.proximoCobro())
                        + ". Ese día te pregunto si se cobró.", Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
            }
        };
    }

    // --- Cobros ---

    /** Botones del cobro de un fijo: confirmar, cambió el monto, este mes no. */
    static final String CONFIRMAR_COBRO = "fc";
    static final String CAMBIO_EL_MONTO = "fm";
    static final String ESTE_MES_NO = "fn";

    /** Esperando el monto de un cobro ("Cambió el monto"): en qué mensaje está la tarjeta. */
    record Espera(long movimientoId, int mensajeId) {
    }

    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();

    /** "Se cobró el seguro: $32.900. ¿Confirmás?" */
    public Accion.Enviar preguntaDeCobro(Movimiento m) {
        return Accion.Enviar.conBotones(textoDeCobro(m), Teclado.constructor()
                .fila(new Teclado.Boton("✅ Confirmar", CONFIRMAR_COBRO + ":" + m.getId()))
                .fila(new Teclado.Boton("✏️ Cambió el monto", CAMBIO_EL_MONTO + ":" + m.getId()),
                        new Teclado.Boton("⏭ Este mes no", ESTE_MES_NO + ":" + m.getId()))
                .build());
    }

    String textoDeCobro(Movimiento m) {
        VistaFijo f = fijos.vista(m.getGastoFijoId());
        return "📌 <b>Se cobró " + html(minuscula(m.getDescripcion())) + ": " + formato.pesos(m.getMonto())
                + ".</b> ¿Confirmás?\n📅 " + formato.fecha(m.getFechaHora()) + " · 💳 "
                + html(m.getTarjeta() != null ? m.getTarjeta().getNombre()
                        : FormatoMensajes.medio(m.getMedioPago(), m.getCuenta()))
                + (f.montoVariable() ? "\n📈 Es el promedio de los últimos cobros: si fue otro, tocá Cambió el monto."
                        : "");
    }

    private static String minuscula(String texto) {
        return texto == null || texto.isEmpty() ? "el gasto fijo"
                : "el " + Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
    }

    public boolean esBotonDeCobro(String accion) {
        return CONFIRMAR_COBRO.equals(accion) || CAMBIO_EL_MONTO.equals(accion) || ESTE_MES_NO.equals(accion);
    }

    public List<Accion> botonDeCobro(long chatId, int mensajeId, String accion, long movimientoId) {
        try {
            return switch (accion) {
                case CONFIRMAR_COBRO -> confirmado(mensajeId, fijos.confirmar(movimientoId, null));
                case CAMBIO_EL_MONTO -> {
                    esperas.put(chatId, new Espera(movimientoId, mensajeId));
                    yield List.of(new Accion.Editar(mensajeId, textoDeCobro(movimientos.buscar(movimientoId))
                            + "\n\n✏️ <b>¿Cuánto se cobró?</b> Escribime el monto.", Teclado.NINGUNO));
                }
                default -> {
                    Movimiento m = fijos.esteMesNo(movimientoId);
                    yield List.of(new Accion.Editar(mensajeId, "⏭ <b>Este mes no</b>\n" + html(m.getDescripcion())
                            + ": no se registra el cobro de " + formato.mes(YearMonth.from(
                                    m.getFijoPeriodo())) + ".", Teclado.NINGUNO), new Accion.Avisar("⏭ Listo"));
                }
            };
        } catch (MovimientoNoPendienteException e) {
            return List.of(new Accion.Editar(mensajeId, "📌 Ese cobro ya estaba resuelto.", Teclado.NINGUNO));
        }
    }

    public boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    public void olvidar(long chatId) {
        esperas.remove(chatId);
    }

    /** El monto que se pidió con "Cambió el monto": el cobro se confirma con ese. */
    public List<Accion> texto(long chatId, String texto) {
        Espera e = esperas.get(chatId);
        Optional<BigDecimal> monto = Montos.parsearConPalabras(texto.replaceAll("(?i)\\s*pesos$", "").strip());
        if (monto.isEmpty()) {
            return List.of(new Accion.Avisar("No entendí el monto: escribilo como 36100 o 36.100"));
        }
        esperas.remove(chatId);
        try {
            return confirmado(e.mensajeId(), fijos.confirmar(e.movimientoId(), monto.get()));
        } catch (MovimientoNoPendienteException ex) {
            return List.of(new Accion.Editar(e.mensajeId(), "📌 Ese cobro ya estaba resuelto.", Teclado.NINGUNO));
        }
    }

    private List<Accion> confirmado(int mensajeId, GastoFijoService.Cobro cobro) {
        Movimiento m = cobro.movimiento();
        String html = "✅ <b>Guardado</b>\n📌 " + html(m.getDescripcion()) + ": " + formato.pesos(m.getMonto())
                + cobro.aumento().map(a -> "\n" + textoDeAumento(a, formato)).orElse("");
        return List.of(new Accion.Editar(mensajeId, html, Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
    }

    /** "📈 El seguro subió de $32.900 a $36.100 (+9,7%)." */
    public static String textoDeAumento(GastoFijoService.Aumento a, FormatoMensajes formato) {
        String porcentaje = a.porcentaje().abs().stripTrailingZeros().toPlainString().replace('.', ',');
        return (a.subio() ? "📈 " : "📉 ") + html(a.nombre()) + (a.subio() ? " subió" : " bajó") + " de "
                + formato.pesos(a.anterior()) + " a " + formato.pesos(a.nuevo()) + " (" + (a.subio() ? "+" : "−")
                + porcentaje + "%).";
    }

    // --- Checklist mensual ---

    /**
     * "/fijos": el checklist del mes en curso (lo cargado, lo que falta y lo salteado), con el total y un botón
     * por cada uno que falta para "este mes no"; y, si hay cobros automáticos esperando respuesta (por si se
     * perdió el mensaje), otra vez su pregunta.
     */
    public List<Accion> lista() {
        YearMonth mes = YearMonth.now(clock);
        List<GastoFijoService.ItemChecklist> items = fijos.checklist(mes);
        List<Accion> acciones = new ArrayList<>();
        acciones.add(Accion.Enviar.conBotones(textoDeChecklist(mes, items), tecladoDeChecklist(items)));
        fijos.cobrosPendientes().forEach(m -> acciones.add(preguntaDeCobro(m)));
        return acciones;
    }

    private String textoDeChecklist(YearMonth mes, List<GastoFijoService.ItemChecklist> items) {
        if (items.isEmpty()) {
            return "📌 No tenés gastos fijos. " + COMO;
        }
        StringBuilder sb = new StringBuilder("📋 <b>Fijos de " + formato.mes(mes) + "</b>");
        BigDecimal cargado = BigDecimal.ZERO;
        int faltan = 0;
        for (GastoFijoService.ItemChecklist item : items) {
            sb.append('\n');
            switch (item.estado()) {
                case CARGADO -> {
                    sb.append("✅ ").append(html(item.nombre())).append(": ").append(formato.pesos(item.monto()));
                    if (item.porcentaje() != null) {
                        sb.append(" (").append(item.porcentaje().signum() > 0 ? "+" : "")
                                .append(item.porcentaje().stripTrailingZeros().toPlainString().replace('.', ','))
                                .append("%)");
                    }
                    cargado = cargado.add(item.monto());
                }
                case ESTE_MES_NO -> sb.append("➖ ").append(html(item.nombre())).append(": este mes no");
                case FALTA -> {
                    sb.append("⏳ ").append(html(item.nombre())).append(": vence el ")
                            .append(item.vencimiento().getDayOfMonth());
                    if (item.montoAnterior() != null) {
                        sb.append(" (ref. ").append(formato.pesos(item.montoAnterior())).append(")");
                    }
                    faltan++;
                }
            }
        }
        sb.append("\n\nCargado: ").append(formato.pesos(cargado));
        if (faltan > 0) {
            sb.append(" · Falta: ").append(faltan);
        }
        return sb.toString();
    }

    private static Teclado tecladoDeChecklist(List<GastoFijoService.ItemChecklist> items) {
        Teclado.Constructor t = Teclado.constructor();
        items.stream().filter(i -> i.estado() == GastoFijoService.EstadoChecklist.FALTA)
                .forEach(i -> t.fila(new Teclado.Boton("⏭ Este mes no: " + i.nombre(),
                        ESTE_MES_NO_CHECKLIST + ":" + i.fijoId())));
        return t.build();
    }

    // --- Recordatorio agrupado ---

    /** "Te falta cargar: luz (vence el 10), gas (vence el 12)." */
    public Accion.Enviar recordatorio(List<RecordatorioFijoService.Vencido> vencidos) {
        String lista = vencidos.stream()
                .map(v -> html(v.nombre()) + " (vence el " + v.diaVencimiento() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        return Accion.Enviar.texto("📌 <b>Te falta cargar:</b> " + lista + ". Mandame el gasto cuando lo tengas.");
    }
}
