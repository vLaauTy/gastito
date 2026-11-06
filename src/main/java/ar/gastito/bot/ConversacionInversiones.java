package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.inversion.InversionService;
import ar.gastito.inversion.NombresInversion;
import ar.gastito.inversion.PanelInversiones;
import ar.gastito.inversion.ParserInversion;
import ar.gastito.inversion.PosicionInversion;
import ar.gastito.inversion.TipoOperacion;
import ar.gastito.inversion.ValuacionInversion;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;

/**
 * Las inversiones en el bot, todo en lenguaje natural y sin IA: el saldo inicial, los aportes y retiros (si no dijo
 * de dónde salió o a dónde fue la plata, pregunta con botones), las compras y ventas dentro de una cuenta, la
 * valuación ("Cocos vale 870.000", se guarda sola con ↩️ Deshacer), las reservas para metas y "¿cuánto tengo
 * invertido?". También el recordatorio "¿Cuánto vale hoy Cocos?" con 🟰 Igual que antes. Lo que cambia algo se
 * confirma con un botón; nada de esto es un gasto ni un ingreso.
 */
@Component
public class ConversacionInversiones {

    /** Botones: "ig:<propuesta>" guardar, "ix:<propuesta>" cancelar, "ic:<propuesta>" el saldo es lo que vale hoy. */
    static final String GUARDAR = "ig";
    static final String CANCELAR = "ix";
    static final String COMO_VALOR = "ic";
    /** De dónde salió o a dónde fue: "io:<propuesta>:<cuenta>" (0: efectivo, −1: la bolsa de la misma cuenta). */
    static final String OTRO_LADO = "io";
    /** Qué posición compró: "ip:<propuesta>:<posición>". */
    static final String POSICION = "ip";
    /** ↩️ Deshacer: "iu:<valuaciones>" una valuación; "iy:<movimiento>" un aporte o retiro; "iz:<op>" una compra. */
    static final String DESHACER_VALOR = "iu";
    static final String DESHACER_MOVIMIENTO = "iy";
    static final String DESHACER_OPERACION = "iz";
    /** Del recordatorio: "ii:<cuenta>" igual que antes, "in:<cuenta>" no preguntar más, "ia:<cuenta>" cargar valor. */
    static final String IGUAL = "ii";
    static final String NO_PREGUNTAR = "in";
    static final String ACTUALIZAR = "ia";
    /** "ie:0": ver las inversiones. */
    static final String VER = "ie";

    /** El atajo: lo mismo que "¿cuánto tengo invertido?" (también el botón 📈 Inversiones del menú). */
    static final String COMANDO = "/inversiones";

    static final long EFECTIVO = 0;
    static final long BOLSA = -1;
    private static final int RECORDADOS = 20;

    private record Propuesta(ParserInversion.Pedido pedido, String texto, Origen origen) {
    }

    private final AtomicLong siguiente = new AtomicLong();
    private final Map<Long, Propuesta> propuestas = recordados();
    private final Map<Long, List<Long>> valuacionesHechas = recordados();
    /** Por chat: la cuenta de la que se espera cuánto vale. */
    private final Map<Long, Long> esperas = new ConcurrentHashMap<>();

    private final InversionService inversiones;
    private final PanelInversiones panel;
    private final CatalogoService catalogos;
    private final FormatoInversiones formato;
    private final Clock clock;

    public ConversacionInversiones(InversionService inversiones, PanelInversiones panel, CatalogoService catalogos,
            FormatoInversiones formato, Clock clock) {
        this.inversiones = inversiones;
        this.panel = panel;
        this.catalogos = catalogos;
        this.formato = formato;
        this.clock = clock;
    }

    private static <V> Map<Long, V> recordados() {
        return Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> mas) {
                return size() > RECORDADOS;
            }
        });
    }

    // --- Mensajes ---

    /** Si el mensaje es de inversiones, la respuesta; si no, vacío (sigue como cualquier mensaje). */
    Optional<List<Accion>> intentar(long chatId, String texto, Origen origen, String prefijo) {
        ParserInversion.Contexto ctx = new ParserInversion.Contexto(inversiones.cuentas(), inversiones.posiciones(),
                catalogos.cargar().cuentas());
        return ParserInversion.parsear(texto, ctx).map(p -> responder(chatId, p, texto, origen, prefijo));
    }

    private List<Accion> responder(long chatId, ParserInversion.Pedido pedido, String texto, Origen origen,
            String prefijo) {
        if (pedido instanceof ParserInversion.Consulta) {
            return consulta(prefijo);
        }
        if (pedido instanceof ParserInversion.Valuacion v) {
            return List.of(valuar(v.cuenta().getId(), v.posicion(), v.valor(), prefijo));
        }
        if (pedido instanceof ParserInversion.LiberarReserva l && reservasDe(l.meta()).isEmpty()) {
            return List.of(Accion.Enviar.texto(prefijo + "🎯 No tenés ninguna reserva para " + html(l.meta()) + "."));
        }
        long id = siguiente.incrementAndGet();
        propuestas.put(id, new Propuesta(pedido, texto, origen));
        return List.of(Accion.Enviar.conBotones(prefijo + propuesta(pedido), teclado(id, pedido)));
    }

    /** "¿Cuánto tengo invertido?": el panel, con un botón para actualizar cada cuenta. */
    List<Accion> consulta(String prefijo) {
        PanelInversiones.Panel p = panel.panel();
        Teclado.Constructor t = Teclado.constructor();
        p.cuentas().forEach(c -> t.fila(new Teclado.Boton("📊 Actualizar " + c.nombre(), ACTUALIZAR + ":" + c.id())));
        return List.of(Accion.Enviar.conBotones(prefijo + formato.panel(p), t.build()));
    }

    /** La IA entendió que habla de sus inversiones pero no qué quiere: lo que tiene y cómo actualizarlo. */
    List<Accion> queHacer(String prefijo) {
        return consulta(prefijo + "🤔 No entendí bien qué querés hacer con tus inversiones. Esto es lo que tenés:\n\n");
    }

    // --- El valor de hoy ---

    boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    void olvidar(long chatId) {
        esperas.remove(chatId);
    }

    /** La respuesta a "¿Cuánto vale hoy Cocos?": un monto o "igual". Si no es eso, se procesa como cualquier mensaje. */
    Optional<List<Accion>> texto(long chatId, String texto) {
        Long cuentaId = esperas.get(chatId);
        if (cuentaId == null) {
            return Optional.empty();
        }
        if (ParserInversion.esIgualQueAntes(texto)) {
            esperas.remove(chatId);
            return Optional.of(List.of(igualQueAntes(cuentaId)));
        }
        Cuenta cuenta = inversiones.cuentas().stream().filter(c -> c.getId().equals(cuentaId)).findFirst().orElse(null);
        Optional<BigDecimal> valor = ParserInversion.unMonto(texto, cuenta);
        esperas.remove(chatId);
        return valor.map(v -> List.of(valuar(cuentaId, null, v, "")));
    }

    /** El recordatorio semanal: queda esperando la respuesta. */
    Accion.Enviar recordatorio(long chatId, InversionService.Recordatorio r) {
        esperas.put(chatId, r.cuentaId());
        return Accion.Enviar.conBotones("📈 <b>¿Cuánto vale hoy " + html(r.cuenta()) + "?</b>\n(última: "
                + formato.pesos(r.valor()) + " " + FormatoInversiones.hace(r.dias()) + ")",
                Teclado.constructor()
                        .fila(new Teclado.Boton("🟰 Igual que antes", IGUAL + ":" + r.cuentaId()))
                        .fila(new Teclado.Boton("🔕 No preguntar más", NO_PREGUNTAR + ":" + r.cuentaId()))
                        .build());
    }

    private Accion.Enviar valuar(long cuentaId, PosicionInversion posicion, BigDecimal valor, String prefijo) {
        try {
            List<ValuacionInversion> hechas = inversiones.valuar(cuentaId,
                    posicion == null ? null : posicion.getId(), valor, hoy());
            return valuado(cuentaId, posicion, hechas, prefijo);
        } catch (IllegalArgumentException e) {
            return Accion.Enviar.texto(prefijo + "🤔 " + html(e.getMessage()) + ".");
        }
    }

    private Accion.Enviar igualQueAntes(long cuentaId) {
        return valuado(cuentaId, null, inversiones.igualQueAntes(cuentaId, hoy()), "🟰 ");
    }

    private Accion.Enviar valuado(long cuentaId, PosicionInversion posicion, List<ValuacionInversion> hechas,
            String prefijo) {
        long id = siguiente.incrementAndGet();
        valuacionesHechas.put(id, hechas.stream().map(ValuacionInversion::getId).toList());
        PanelInversiones.EstadoCuenta c = panel.cuenta(cuentaId);
        String titulo;
        if (posicion != null) {
            PanelInversiones.Tenencia t = c.tenencias().stream()
                    .filter(x -> posicion.getId().equals(x.posicionId())).findFirst().orElseThrow();
            titulo = "📊 <b>" + html(t.nombre()) + " vale " + formato.pesos(t.valor()) + "</b> (hoy)\nPuesto "
                    + formato.pesos(t.aportado()) + " · " + formato.rendimiento(t.rendimiento(), t.porcentaje())
                    + "\n\n" + formato.cuenta(c);
        } else {
            titulo = "📊 <b>" + html(c.nombre()) + " vale " + formato.pesos(c.valor()) + "</b> (hoy)\n"
                    + formato.cuenta(c);
        }
        return Accion.Enviar.conBotones(prefijo + titulo + "\n\nEs ahorro: no cuenta como ingreso.",
                Teclado.constructor().fila(new Teclado.Boton("↩️ Deshacer", DESHACER_VALOR + ":" + id)).build());
    }

    // --- Propuestas ---

    private String propuesta(ParserInversion.Pedido pedido) {
        return switch (pedido) {
            case ParserInversion.SaldoInicial s -> saldoPropuesto(s) + "Al " + dia(hoy())
                    + ". Es plata que ya tenías ahorrada: no cuenta como ingreso.";
            case ParserInversion.SaldosIniciales v -> String.join("\n", v.cuentas().stream()
                    .map(this::saldoPropuesto).toList()) + "Al " + dia(hoy())
                    + ". Es plata que ya tenías ahorrada: no cuenta como ingreso.";
            case ParserInversion.Aporte a -> "📥 <b>Aporte a " + html(a.cuenta().getNombre()) + "</b>: "
                    + formato.pesos(a.monto()) + (a.origenDicho() ? " desde " + lado(a.desde()) : "")
                    + (a.posicion() == null ? "" : " → " + html(a.posicion().getNombre()))
                    + (a.origenDicho() ? "\nEs un pase entre cuentas: no cuenta como gasto ni como ingreso."
                            : "\n<b>¿De dónde salió la plata?</b>");
            case ParserInversion.Retiro r -> "📤 <b>Retiro de " + html(r.cuenta().getNombre()) + "</b>"
                    + (r.posicion() == null ? "" : " (" + html(r.posicion().getNombre()) + ")") + ": "
                    + formato.pesos(r.monto()) + (r.destinoDicho() ? " a " + lado(r.hacia()) : "")
                    + (r.destinoDicho() ? "\nEs un pase entre cuentas: no cuenta como gasto ni como ingreso."
                            : "\n<b>¿A dónde fue la plata?</b>");
            case ParserInversion.Compra c -> c.posicion() == null && c.posicionNueva() == null
                    ? "🔁 <b>Compra en " + html(c.cuenta().getNombre()) + "</b>: " + formato.pesos(c.monto())
                            + " de lo que había sin invertir.\n<b>¿Qué compraste?</b>"
                    : "🔁 <b>Compra en " + html(c.cuenta().getNombre()) + "</b>: " + formato.pesos(c.monto())
                            + " de lo que había sin invertir → " + html(c.posicion() != null
                                    ? c.posicion().getNombre() : c.posicionNueva() + " (posición nueva)")
                            + "\nQueda dentro de la cuenta: no es un gasto.";
            case ParserInversion.Venta v -> "🔁 <b>Venta en " + html(v.cuenta().getNombre()) + "</b>: "
                    + formato.pesos(v.monto()) + " de " + html(v.posicion().getNombre())
                    + " → queda sin invertir en la cuenta.\nNo es un ingreso.";
            case ParserInversion.Reserva r -> "🎯 <b>Reservar " + formato.pesos(r.monto()) + " de "
                    + html(r.cuenta().getNombre()) + " para " + html(r.meta()) + "</b>\nSigue invertido: solo "
                    + "queda apartado para esa meta.";
            case ParserInversion.LiberarReserva l -> "🎯 <b>¿Libero la reserva para " + html(l.meta()) + "?</b>\n"
                    + String.join("\n", reservasDe(l.meta()).stream()
                            .map(r -> "• " + html(r.cuenta()) + ": " + formato.pesos(r.monto())).toList());
            default -> "";
        };
    }

    /** "📈 Saldo inicial de Cocos (cuenta nueva)", el total y una línea por posición. */
    private String saldoPropuesto(ParserInversion.SaldoInicial s) {
        StringBuilder sb = new StringBuilder("📈 <b>Saldo inicial de ")
                .append(html(s.cuenta() != null ? s.cuenta().getNombre() : s.nombreCuenta())).append("</b>")
                .append(s.cuenta() == null ? " (cuenta de inversión nueva)" : "").append("\n");
        if (s.total() != null) {
            sb.append("Total: <b>").append(formato.pesos(s.total())).append("</b>\n");
        }
        s.posiciones().forEach(p -> sb.append("• ").append(html(p.posicion())).append(": ")
                .append(formato.pesos(p.monto())).append("\n"));
        return sb.toString();
    }

    /** Lo guardado de una cuenta: dónde, si reemplazó un saldo anterior, y cómo quedó. */
    private String saldoGuardado(InversionService.CargaInicial carga) {
        StringBuilder sb = new StringBuilder("✅ <b>Saldo inicial guardado</b> en ")
                .append(html(carga.cuenta().getNombre()))
                .append(carga.cuentaNueva() ? " (cuenta de inversión nueva)" : "").append(".");
        carga.reemplazados().forEach(r -> sb.append("\nReemplazó el anterior")
                .append(r.posicion() == null ? "" : " de " + html(r.posicion())).append(" (")
                .append(formato.pesos(r.monto())).append(")."));
        return sb.append("\n").append(formato.cuenta(panel.cuenta(carga.cuenta().getId()))).toString();
    }

    private static InversionService.SaldoDeCuenta deCuenta(ParserInversion.SaldoInicial s) {
        return new InversionService.SaldoDeCuenta(s.cuenta() == null ? null : s.cuenta().getId(), s.nombreCuenta(),
                s.total(), s.posiciones());
    }

    private Teclado teclado(long id, ParserInversion.Pedido pedido) {
        Teclado.Constructor t = Teclado.constructor();
        if (pedido instanceof ParserInversion.Aporte a && !a.origenDicho()) {
            ladosConBotones(t, id, a.posicion() != null ? "📦 Lo que había sin invertir en " + a.cuenta().getNombre()
                    : null);
        } else if (pedido instanceof ParserInversion.Retiro r && !r.destinoDicho()) {
            ladosConBotones(t, id, r.posicion() != null ? "📦 Queda en " + r.cuenta().getNombre() + " sin invertir"
                    : null);
        } else if (pedido instanceof ParserInversion.Compra c && c.posicion() == null && c.posicionNueva() == null) {
            List<Teclado.Boton> botones = inversiones.posiciones().stream()
                    .filter(p -> p.getCuenta().getId().equals(c.cuenta().getId()))
                    .map(p -> new Teclado.Boton(p.getNombre(), POSICION + ":" + id + ":" + p.getId())).toList();
            t.grilla(botones, 2);
        } else {
            t.fila(new Teclado.Boton("✅ Guardar", GUARDAR + ":" + id));
            if (pedido instanceof ParserInversion.SaldoInicial s && s.cuenta() != null && s.total() != null) {
                t.fila(new Teclado.Boton("📊 Es lo que vale hoy", COMO_VALOR + ":" + id));
            }
        }
        return t.fila(new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id)).build();
    }

    private void ladosConBotones(Teclado.Constructor t, long id, String bolsa) {
        List<Teclado.Boton> botones = new ArrayList<>();
        catalogos.cargar().cuentas().forEach(c -> botones.add(new Teclado.Boton("🏦 " + c.getNombre(),
                OTRO_LADO + ":" + id + ":" + c.getId())));
        botones.add(new Teclado.Boton("💵 Efectivo", OTRO_LADO + ":" + id + ":" + EFECTIVO));
        t.grilla(botones, 2);
        if (bolsa != null) {
            t.fila(new Teclado.Boton(bolsa, OTRO_LADO + ":" + id + ":" + BOLSA));
        }
    }

    // --- Botones ---

    List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido) {
        try {
            return switch (accion) {
                case GUARDAR -> guardar(mensajeId, id, propuesta(id).pedido(), null);
                case OTRO_LADO, POSICION -> {
                    if (elegido == null) {
                        throw new NoSuchElementException("Falta qué eligió");
                    }
                    yield guardar(mensajeId, id, propuesta(id).pedido(), elegido);
                }
                case COMO_VALOR -> {
                    Propuesta p = propuestas.remove(id);
                    if (p == null || !(p.pedido() instanceof ParserInversion.SaldoInicial s) || s.cuenta() == null) {
                        throw new NoSuchElementException("Esa propuesta ya no está");
                    }
                    yield List.of(editar(mensajeId, valuar(s.cuenta().getId(), null, s.total(), "")));
                }
                case CANCELAR -> {
                    propuestas.remove(id);
                    yield List.of(new Accion.Editar(mensajeId, "❌ Cancelado: no cambié nada.", Teclado.NINGUNO),
                            new Accion.Avisar("Cancelado"));
                }
                case DESHACER_VALOR -> {
                    List<Long> ids = valuacionesHechas.remove(id);
                    if (ids == null) {
                        throw new NoSuchElementException("Ya no puedo deshacer ese valor: borralo en el dashboard");
                    }
                    inversiones.borrarValuaciones(ids);
                    yield List.of(new Accion.Editar(mensajeId, "↩️ Deshecho: el valor quedó como antes.",
                            Teclado.NINGUNO), new Accion.Avisar("Deshecho"));
                }
                case DESHACER_MOVIMIENTO -> {
                    Movimiento m = inversiones.borrarMovimiento(id);
                    yield List.of(new Accion.Editar(mensajeId, "↩️ Deshecho: borré " + html(m.getDescripcion())
                            + " (" + formato.pesos(m.getMonto()) + ").", Teclado.NINGUNO), new Accion.Avisar("Deshecho"));
                }
                case DESHACER_OPERACION -> {
                    inversiones.borrarOperacion(id);
                    yield List.of(new Accion.Editar(mensajeId, "↩️ Deshecho.", Teclado.NINGUNO),
                            new Accion.Avisar("Deshecho"));
                }
                case IGUAL -> {
                    esperas.remove(chatId);
                    yield List.of(editar(mensajeId, igualQueAntes(id)));
                }
                case NO_PREGUNTAR -> {
                    esperas.remove(chatId);
                    Cuenta c = inversiones.cambiarRecordatorio(id, null);
                    yield List.of(new Accion.Editar(mensajeId, "🔕 Listo: no te pregunto más cuánto vale "
                            + html(c.getNombre()) + ". Lo actualizás cuando quieras diciéndomelo, o en el dashboard "
                            + "(ahí también se vuelve a prender el recordatorio).", Teclado.NINGUNO));
                }
                case ACTUALIZAR -> {
                    PanelInversiones.EstadoCuenta c = panel.cuenta(id);
                    esperas.put(chatId, id);
                    yield List.of(Accion.Enviar.conBotones("📈 <b>¿Cuánto vale hoy " + html(c.nombre()) + "?</b>\n"
                            + "(última: " + formato.pesos(c.valor()) + " " + FormatoInversiones.hace(c.diasSinActualizar())
                            + ")", Teclado.constructor()
                                    .fila(new Teclado.Boton("🟰 Igual que antes", IGUAL + ":" + id)).build()));
                }
                case VER -> consulta("");
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return List.of(new Accion.Editar(mensajeId, "🤔 " + html(e.getMessage()) + ".", Teclado.NINGUNO));
        }
    }

    private Propuesta propuesta(long id) {
        Propuesta p = propuestas.get(id);
        if (p == null) {
            throw new NoSuchElementException("Esa propuesta ya no está");
        }
        return p;
    }

    /**
     * Guarda lo propuesto.
     *
     * @param elegido de dónde salió o a dónde fue la plata (una cuenta, 0 efectivo, −1 la bolsa), o qué posición
     *                compró; null si ya estaba todo dicho
     */
    private List<Accion> guardar(int mensajeId, long id, ParserInversion.Pedido pedido, Long elegido) {
        Propuesta p = propuesta(id);
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        // Un aporte o un retiro guardado se puede deshacer: el botón que va en la tarjeta.
        String[] deshacer = {null};
        String texto = switch (pedido) {
            case ParserInversion.SaldoInicial s -> saldoGuardado(
                    inversiones.cargarSaldosIniciales(List.of(deCuenta(s)), hoy(), p.texto()).getFirst());
            case ParserInversion.SaldosIniciales v -> String.join("\n\n", inversiones.cargarSaldosIniciales(
                    v.cuentas().stream().map(ConversacionInversiones::deCuenta).toList(), hoy(), p.texto())
                    .stream().map(this::saldoGuardado).toList());
            case ParserInversion.Aporte a when elegido != null && elegido == BOLSA -> compra(a.cuenta().getId(),
                    a.posicion(), null, a.monto(), p.texto());
            case ParserInversion.Aporte a -> {
                Long desde = elegido == null ? (a.desde() == null ? null : a.desde().getId())
                        : elegido == EFECTIVO ? null : elegido;
                Movimiento m = inversiones.aportar(a.cuenta().getId(), a.posicion() == null ? null
                        : a.posicion().getId(), a.monto(), desde, ahora, p.origen(), p.texto());
                deshacer[0] = DESHACER_MOVIMIENTO + ":" + m.getId();
                yield "✅ <b>Aporte guardado</b>: " + formato.pesos(m.getMonto()) + " desde " + lado(m.getCuenta())
                        + " a " + html(a.cuenta().getNombre()) + "\nNo cuenta como gasto ni como ingreso.\n\n"
                        + formato.cuenta(panel.cuenta(a.cuenta().getId()));
            }
            case ParserInversion.Retiro r when elegido != null && elegido == BOLSA -> {
                inversiones.operar(TipoOperacion.VENTA, r.cuenta().getId(), r.posicion().getId(), r.monto(), hoy(),
                        p.texto());
                yield "✅ <b>Venta guardada</b>: " + formato.pesos(r.monto()) + " de " + html(r.posicion().getNombre())
                        + " quedan sin invertir en " + html(r.cuenta().getNombre()) + ".\n\n"
                        + formato.cuenta(panel.cuenta(r.cuenta().getId()));
            }
            case ParserInversion.Retiro r -> {
                Long hacia = elegido == null ? (r.hacia() == null ? null : r.hacia().getId())
                        : elegido == EFECTIVO ? null : elegido;
                Movimiento m = inversiones.retirar(r.cuenta().getId(), r.posicion() == null ? null
                        : r.posicion().getId(), r.monto(), hacia, ahora, p.origen(), p.texto());
                deshacer[0] = DESHACER_MOVIMIENTO + ":" + m.getId();
                yield "✅ <b>Retiro guardado</b>: " + formato.pesos(m.getMonto()) + " de "
                        + html(r.cuenta().getNombre()) + " a " + lado(m.getCuentaDestino())
                        + "\nNo cuenta como gasto ni como ingreso.\n\n" + formato.cuenta(panel.cuenta(r.cuenta().getId()));
            }
            case ParserInversion.Compra c -> {
                PosicionInversion posicion = c.posicion() != null ? c.posicion() : null;
                Long posicionId = elegido != null ? elegido : posicion == null ? null : posicion.getId();
                yield compra(c.cuenta().getId(), posicionId == null ? null
                        : inversiones.posiciones().stream().filter(x -> x.getId().equals(posicionId)).findFirst()
                                .orElseThrow(() -> new NoSuchElementException("No existe esa posición")),
                        c.posicionNueva(), c.monto(), p.texto());
            }
            case ParserInversion.Venta v -> {
                inversiones.operar(TipoOperacion.VENTA, v.cuenta().getId(), v.posicion().getId(), v.monto(), hoy(),
                        p.texto());
                yield "✅ <b>Venta guardada</b>: " + formato.pesos(v.monto()) + " de " + html(v.posicion().getNombre())
                        + " quedan sin invertir.\n\n" + formato.cuenta(panel.cuenta(v.cuenta().getId()));
            }
            case ParserInversion.Reserva r -> {
                inversiones.reservar(r.cuenta().getId(), r.meta(), r.monto());
                yield "🎯 <b>Reservado</b>: " + formato.pesos(r.monto()) + " de " + html(r.cuenta().getNombre())
                        + " para " + html(r.meta()) + ".\n\n" + formato.cuenta(panel.cuenta(r.cuenta().getId()));
            }
            case ParserInversion.LiberarReserva l -> {
                BigDecimal total = inversiones.liberarMeta(l.meta()).stream().map(r -> r.getMonto())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                yield "🎯 Liberé la reserva para " + html(l.meta()) + " (" + formato.pesos(total) + ").";
            }
            default -> throw new IllegalStateException("No hay nada para guardar");
        };
        propuestas.remove(id);
        Teclado teclado = deshacer[0] == null ? Teclado.NINGUNO
                : Teclado.constructor().fila(new Teclado.Boton("↩️ Deshacer", deshacer[0])).build();
        return List.of(new Accion.Editar(mensajeId, texto, teclado), new Accion.Avisar("✅ Guardado"));
    }

    private String compra(long cuentaId, PosicionInversion posicion, String nueva, BigDecimal monto, String texto) {
        PosicionInversion p = posicion != null ? posicion : inversiones.posicion(cuentaId, nueva);
        inversiones.operar(TipoOperacion.COMPRA, cuentaId, p.getId(), monto, hoy(), texto);
        return "✅ <b>Compra guardada</b>: " + formato.pesos(monto) + " de lo que había sin invertir → "
                + html(p.getNombre()) + ".\nQueda dentro de la cuenta: no es un gasto.\n\n"
                + formato.cuenta(panel.cuenta(cuentaId));
    }

    private static Accion editar(int mensajeId, Accion.Enviar e) {
        return new Accion.Editar(mensajeId, e.html(), e.teclado());
    }

    private record ReservaVista(String cuenta, BigDecimal monto) {
    }

    private List<ReservaVista> reservasDe(String meta) {
        String buscada = NombresInversion.normalizar(meta);
        List<ReservaVista> encontradas = new ArrayList<>();
        for (PanelInversiones.EstadoCuenta c : panel.panel().cuentas()) {
            c.reservas().stream().filter(r -> NombresInversion.normalizar(r.meta()).equals(buscada))
                    .forEach(r -> encontradas.add(new ReservaVista(c.nombre(), r.monto())));
        }
        return encontradas;
    }

    private static String lado(Cuenta c) {
        return c == null ? "el efectivo" : html(c.getNombre());
    }

    private LocalDate hoy() {
        return LocalDate.now(clock);
    }

    private static String dia(LocalDate fecha) {
        return fecha.getDayOfMonth() + "/" + fecha.getMonthValue();
    }
}
