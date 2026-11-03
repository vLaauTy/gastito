package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import ar.gastito.dolar.CompraEnDolaresService;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ParserDolar;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;

/**
 * Las compras en dólares con la tarjeta en el bot: si se dijo otro medio de pago, pregunta; si no hay cotización de
 * referencia, la pide (con "No sé": queda "≈ sin estimar"); "/dolar 1500" o "el dólar tarjeta está a 1500" la cambia;
 * y "lo de Steam salió 31.200" propone corregir la compra con lo que cobraron.
 */
@Component
public class ConversacionDolares {

    /** Dijo dólares con otro medio: "Fue con tarjeta de crédito" ("yt:<id>") o "Eran pesos" ("yp:<id>"). */
    static final String CON_TARJETA = "yt";
    static final String EN_PESOS = "yp";
    /** "No sé" la cotización: "yn:<id>". */
    static final String NO_SE = "yn";
    /** "Corregir el monto" de una compra en dólares con el gasto que se acaba de decir: "yk:<nuevo>:<compra>". */
    static final String CORREGIR = "yk";
    /** "Es otro gasto": sigue como un gasto nuevo, "yo:<id>". */
    static final String OTRO_GASTO = "yo";

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    /** Por chat: la compra en dólares que espera la cotización. */
    private final Map<Long, Long> esperas = new ConcurrentHashMap<>();

    private final CompraEnDolaresService compras;
    private final MovimientoService movimientos;
    private final FormatoMensajes formato;

    public ConversacionDolares(CompraEnDolaresService compras, MovimientoService movimientos,
            FormatoMensajes formato) {
        this.compras = compras;
        this.movimientos = movimientos;
        this.formato = formato;
    }

    /**
     * Un gasto pendiente recién interpretado en dólares (el monto todavía es el número que dijo).
     *
     * @param seguir la tarjeta de siempre (guardado solo o para confirmar), cuando ya no falta nada
     */
    Accion.Enviar nueva(long chatId, Movimiento m, String prefijo, Function<Movimiento, Accion.Enviar> seguir) {
        if (m.getMedioPago() != null && !m.getMedioPago().esTarjetaDeCredito()) {
            return Accion.Enviar.conBotones(prefijo + "💵 <b>" + formato.dolaresCompra(m.getMonto()) + "</b>"
                    + descripcion(m) + " con " + html(m.getMedioPago().getNombre()) + ".\nLas compras en dólares "
                    + "las anoto solo con la tarjeta de crédito. ¿Cómo fue?",
                    Teclado.constructor()
                            .fila(new Teclado.Boton("💳 Con la tarjeta de crédito", CON_TARJETA + ":" + m.getId()))
                            .fila(new Teclado.Boton("💲 Eran " + formato.pesos(m.getMonto()) + " (pesos)",
                                    EN_PESOS + ":" + m.getId()))
                            .fila(new Teclado.Boton("❌ Cancelar", Tarjetas.CANCELAR + ":" + m.getId()))
                            .build());
        }
        return enDolares(chatId, compras.pasarADolares(m.getId(), m.getMonto()), prefijo, seguir);
    }

    /** Ya está en dólares: sin cotización la pide; si no, sigue (la tarjeta ya muestra lo que se escuchó). */
    private Accion.Enviar enDolares(long chatId, Movimiento m, String prefijo,
            Function<Movimiento, Accion.Enviar> seguir) {
        if (!m.isSinEstimar()) {
            return seguir.apply(m);
        }
        esperas.put(chatId, m.getId());
        return Accion.Enviar.conBotones(prefijo + "💵 <b>" + formato.dolaresCompra(m.getMontoOriginal()) + "</b>"
                + descripcion(m) + " con la tarjeta.\n¿A cuánto está el dólar tarjeta? Con eso estimo los pesos; lo "
                + "real lo corregís cuando llegue el resumen.",
                Teclado.constructor().fila(new Teclado.Boton("🤷 No sé", NO_SE + ":" + m.getId())).build());
    }

    // --- La cotización ---

    boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    void olvidar(long chatId) {
        esperas.remove(chatId);
    }

    /**
     * La cotización que se pidió ("1500", "$1.500", "está a 1500"). Si el texto no sirve, se olvida y el mensaje
     * se procesa normal.
     */
    Optional<List<Accion>> texto(long chatId, String texto, Function<Movimiento, Accion.Enviar> seguir) {
        Long id = esperas.remove(chatId);
        if (id == null) {
            return Optional.empty();
        }
        String limpio = texto.replace("$", "").replaceAll("(?i)^(?:est[aá]\\s+)?a\\s+", "").strip();
        return Montos.parsearConPalabras(limpio).or(() -> ParserDolar.cotizacion(texto))
                .map(valor -> cotizacion(valor, id, seguir));
    }

    /**
     * "/dolar 1500", "el dólar tarjeta está a 1500": queda como referencia y estima las compras que estaban sin
     * estimar. Sin valor, muestra la actual.
     */
    List<Accion> cotizacion(long chatId, BigDecimal valor, String prefijo, Function<Movimiento, Accion.Enviar> seguir) {
        if (valor == null) {
            return List.of(Accion.Enviar.texto(prefijo + compras.referencia()
                    .map(c -> "💵 El dólar tarjeta de referencia está a <b>" + formato.pesos(c.getValor())
                            + "</b> (desde el " + DIA.format(c.getCargadaEn()) + ").\nSi cambió, decime a cuánto está.")
                    .orElse("💵 Todavía no sé a cuánto está el dólar tarjeta. Decime cuánto y con eso estimo los "
                            + "pesos de las compras en dólares.")));
        }
        Long esperando = esperas.remove(chatId);
        List<Accion> acciones = cotizacion(valor, esperando, seguir);
        if (!prefijo.isEmpty() && acciones.getFirst() instanceof Accion.Enviar e) {
            acciones.set(0, new Accion.Enviar(prefijo + e.html(), e.teclado(), e.conMenu(), e.tarjetaDeMovimiento()));
        }
        return acciones;
    }

    /** Carga la cotización y, si una compra pendiente la esperaba, sigue con su tarjeta. */
    private List<Accion> cotizacion(BigDecimal valor, Long esperando, Function<Movimiento, Accion.Enviar> seguir) {
        CompraEnDolaresService.CotizacionCargada cargada = compras.cargarCotizacion(valor);
        StringBuilder texto = new StringBuilder("💵 Listo: el dólar tarjeta queda a <b>")
                .append(formato.pesos(cargada.cotizacion().getValor()))
                .append("</b>. Lo uso para estimar las compras en dólares hasta que llegue el resumen.");
        List<Movimiento> guardadas = cargada.estimadas().stream().filter(m -> !m.isPendiente()).toList();
        if (!guardadas.isEmpty()) {
            texto.append("\nEstimé las que estaban sin estimar:");
            guardadas.forEach(m -> texto.append("\n• ").append(m.getDescripcion() == null ? "Compra"
                    : html(m.getDescripcion())).append(": ").append(formato.monto(m)));
        }
        List<Accion> acciones = new ArrayList<>();
        acciones.add(Accion.Enviar.conMenu(texto.toString()));
        if (esperando != null) {
            Movimiento m = movimientos.buscar(esperando);
            if (m.isPendiente()) {
                acciones.add(seguir.apply(m));
            }
        }
        return acciones;
    }

    // --- Lo de Steam salió 31.200 ---

    /** Si el gasto nuevo es lo que cobraron por una compra en dólares estimada: propone corregirla. */
    Optional<Accion.Enviar> siCorrigeUnaCompra(Movimiento nuevo) {
        return compras.estimadaParecida(nuevo).map(compra -> Accion.Enviar.conBotones("💵 <b>"
                + html(compra.getDescripcion()) + "</b> está cargada en dólares: " + formato.monto(compra)
                + ".\n¿Es lo que te cobraron?",
                Teclado.constructor()
                        .fila(new Teclado.Boton("🔄 Corregir el monto (" + (compra.isSinEstimar() ? "sin estimar"
                                : formato.pesos(compra.getMonto())) + " → " + formato.pesos(nuevo.getMonto()) + ")",
                                CORREGIR + ":" + nuevo.getId() + ":" + compra.getId()))
                        .fila(new Teclado.Boton("➕ Es otro gasto", OTRO_GASTO + ":" + nuevo.getId()))
                        .build()));
    }

    // --- Botones ---

    /**
     * @param seguir     la tarjeta de siempre de un gasto pendiente (guardado solo o para confirmar)
     * @param principal  la tarjeta del gasto pendiente en el lugar del mensaje (para "Es otro gasto")
     */
    List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido,
            Function<Movimiento, Accion.Enviar> seguir, Function<Movimiento, Accion> principal) {
        try {
            return switch (accion) {
                case CON_TARJETA -> {
                    Movimiento m = movimientos.buscar(id);
                    exigirPendiente(m);
                    yield List.of(new Accion.Editar(mensajeId, "💳 Con la tarjeta de crédito", Teclado.NINGUNO),
                            enDolares(chatId, compras.pasarADolares(id, m.getMonto()), "", seguir));
                }
                case EN_PESOS -> {
                    Movimiento m = movimientos.buscar(id);
                    exigirPendiente(m);
                    yield List.of(new Accion.Editar(mensajeId, "💲 Eran pesos", Teclado.NINGUNO), seguir.apply(m));
                }
                case NO_SE -> {
                    esperas.remove(chatId);
                    Movimiento m = movimientos.buscar(id);
                    exigirPendiente(m);
                    yield List.of(new Accion.Editar(mensajeId, "🤷 Sin cotización: queda <b>"
                            + formato.monto(m) + "</b> hasta que me digas a cuánto está el dólar o cuánto te "
                            + "cobraron.", Teclado.NINGUNO), seguir.apply(m));
                }
                case CORREGIR -> {
                    if (elegido == null) {
                        throw new NoSuchElementException("Falta la compra");
                    }
                    CompraEnDolaresService.Correccion c = compras.corregirCon(id, elegido);
                    Movimiento compra = c.compra();
                    yield List.of(new Accion.Editar(mensajeId, "✅ <b>Monto corregido</b>\n💵 "
                            + html(compra.getDescripcion()) + ": " + formato.dolaresCompra(compra.getMontoOriginal())
                            + " · " + (c.anterior().signum() == 0 ? "sin estimar" : formato.pesos(c.anterior()))
                            + " → <b>" + formato.pesos(compra.getMonto()) + "</b> (el dólar salió "
                            + formato.pesos(compra.getCotizacionUsada()) + ")", Teclado.NINGUNO),
                            new Accion.Avisar("✅ Corregido"));
                }
                case OTRO_GASTO -> {
                    Movimiento m = movimientos.buscar(id);
                    exigirPendiente(m);
                    yield List.of(principal.apply(m));
                }
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (IllegalStateException | IllegalArgumentException e) {
            return List.of(new Accion.Editar(mensajeId, "🤔 " + html(e.getMessage()) + ".", Teclado.NINGUNO));
        }
    }

    private static void exigirPendiente(Movimiento m) {
        if (!m.isPendiente()) {
            throw new IllegalStateException("Ese gasto ya no está pendiente");
        }
    }

    private static String descripcion(Movimiento m) {
        return m.getDescripcion() == null || m.getDescripcion().isBlank() ? "" : " en " + html(m.getDescripcion());
    }
}
