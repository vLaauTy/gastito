package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ParserFijo;
import ar.gastito.interpretacion.PedidoFijo;

/**
 * El alta de un gasto fijo en el chat, dicho como sea ("agregame un fijo: el seguro de la moto, unos 32 mil por mes
 * con la tarjeta"). Se muestra la tarjeta con lo que se entendió y lo que falta se pregunta de a una cosa, con
 * botones: si ya existe uno parecido, la categoría, el medio de pago, el día (con "Otro" y "No sé") y el monto (por
 * texto o audio, con "No sé"). Nunca se le pide al usuario un formato de comando.
 */
@Component
public class ConversacionAltaFijo {

    static final String GUARDAR = "fa";
    static final String CANCELAR = "fz";
    static final String CATEGORIA = "fk";
    /** Día elegido; {@link #NO_SE} si no sabe, {@link #OTRO} si lo escribe. */
    static final String DIA = "ad";
    /** Medio de pago elegido; {@link #NO_SE} si no sabe. */
    static final String MEDIO = "am";
    static final String MONTO_NO_SE = "an";
    static final String EDITAR = "ae";
    /** Un dato a cambiar desde "Editar": el ordinal de {@link Dato}. */
    static final String CAMBIAR = "aq";
    static final String FRECUENCIA = "af";
    static final String VOLVER = "aw";
    /** Ya existía uno parecido: actualizar su monto o seguir con el alta. */
    static final String ACTUALIZAR_EXISTENTE = "av";
    static final String ES_OTRO = "ao";

    static final long NO_SE = 0;
    static final long OTRO = 99;
    static final List<Integer> DIAS_FRECUENTES = List.of(1, 5, 10, 15, 20, 25);
    static final int ALTAS_RECORDADAS = 10;

    private static final DateTimeFormatter DIA_DE_SEMANA =
            DateTimeFormatter.ofPattern("EEEE d/M", Locale.forLanguageTag("es-AR"));
    private static final Pattern NUMERO_DE_DIA = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)");
    private static final Pattern MONTO_EN_TEXTO = Pattern.compile(
            "(?i)(\\d[\\d.,]*)\\s*(mil|k|lucas|luquitas|millones|millón|millon)?\\b");

    /** Lo que se puede cambiar desde "Editar" (o lo que se está preguntando). */
    enum Dato {
        MONTO, DIA, CATEGORIA, MEDIO, NOMBRE
    }

    /**
     * Un alta en curso.
     *
     * @param sinDia       dijo "No sé" al día: se guarda sin día
     * @param sinMonto     dijo "No sé" al monto: se guarda sin monto de referencia
     * @param sinMedio     dijo "No sé" al medio de pago
     * @param parecidoVisto ya se le avisó del fijo parecido (o no había)
     */
    record Alta(PedidoFijo pedido, boolean sinDia, boolean sinMonto, boolean sinMedio, boolean parecidoVisto) {

        Alta con(PedidoFijo nuevo) {
            return new Alta(nuevo, sinDia, sinMonto, sinMedio, parecidoVisto);
        }
    }

    /** Se espera que el usuario escriba (o diga) un dato del alta. */
    record Espera(long altaId, Dato dato) {
    }

    private final GastoFijoService fijos;
    private final CatalogoService catalogos;
    private final FormatoMensajes formato;
    private final ParserFijo parser;

    private final AtomicLong siguiente = new AtomicLong();
    private final Map<Long, Alta> altas = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Alta> mas) {
            return size() > ALTAS_RECORDADAS;
        }
    });
    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();

    public ConversacionAltaFijo(GastoFijoService fijos, CatalogoService catalogos, FormatoMensajes formato,
            ParserFijo parser) {
        this.fijos = fijos;
        this.catalogos = catalogos;
        this.formato = formato;
        this.parser = parser;
    }

    // --- Entrada ---

    /** La tarjeta del alta, ya con la primera pregunta si falta algo. */
    public Accion.Enviar nueva(long chatId, PedidoFijo pedido, String prefijo) {
        long id = siguiente.incrementAndGet();
        Alta alta = new Alta(pedido, false, false, false, false);
        altas.put(id, alta);
        Pantalla p = siguientePaso(chatId, id, alta);
        return Accion.Enviar.conBotones(prefijo + p.texto(), p.teclado());
    }

    /**
     * El atajo "/fijo seguro 32900 tarjeta día 4", sin IA. Vacío si el parser no lo entiende: entonces lo interpreta
     * la IA, como cualquier mensaje.
     */
    public Optional<Accion.Enviar> desdeComando(long chatId, String argumentos) {
        return parser.parsear(argumentos, catalogos.cargar()).map(p -> nueva(chatId, p, ""));
    }

    /** "¿Qué gasto fijo querés agregar?": el alta sin nada dicho todavía. */
    public static String preguntaInicial() {
        return "📌 ¿Qué gasto fijo querés agregar? Contámelo como quieras, por ejemplo: "
                + "<i>el seguro de la moto, unos 32 mil por mes con la tarjeta</i>.";
    }

    public boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    public void olvidar(long chatId) {
        esperas.remove(chatId);
    }

    /**
     * Lo que escribió (o dijo) el usuario cuando se le preguntó un dato. Vacío si no sirve para ese dato: la
     * pregunta se olvida y el mensaje se procesa como cualquier otro.
     */
    public Optional<Accion.Enviar> texto(long chatId, String texto) {
        Espera e = esperas.remove(chatId);
        Alta alta = e == null ? null : altas.get(e.altaId());
        if (alta == null) {
            return Optional.empty();
        }
        Alta nueva = switch (e.dato()) {
            case MONTO -> monto(texto).map(m -> alta.con(alta.pedido().conMonto(m))).orElse(null);
            case DIA -> dia(texto).map(d -> new Alta(alta.pedido().conDia(d), false, alta.sinMonto(),
                    alta.sinMedio(), alta.parecidoVisto())).orElse(null);
            case NOMBRE -> texto.isBlank() || texto.length() > 80 ? null
                    : alta.con(alta.pedido().conNombre(texto.strip()));
            default -> null;
        };
        if (nueva == null) {
            return Optional.empty();
        }
        altas.put(e.altaId(), nueva);
        Pantalla p = siguientePaso(chatId, e.altaId(), nueva);
        return Optional.of(Accion.Enviar.conBotones(p.texto(), p.teclado()));
    }

    /** "32000", "unos 32 mil", "32 lucas", "$32.500 pesos": el primer monto del texto. */
    static Optional<BigDecimal> monto(String texto) {
        Matcher m = MONTO_EN_TEXTO.matcher(texto);
        if (!m.find()) {
            return Optional.empty();
        }
        String palabra = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        String sufijo = palabra.startsWith("luc") ? " mil" : palabra.isEmpty() ? "" : " " + palabra;
        return Montos.parsearConPalabras(m.group(1).replaceAll("[.,]$", "") + sufijo);
    }

    /** "4", "el 4", "día 10": un día del 1 al 31. */
    static Optional<Integer> dia(String texto) {
        Matcher m = NUMERO_DE_DIA.matcher(texto);
        if (!m.find()) {
            return Optional.empty();
        }
        int d = Integer.parseInt(m.group(1));
        return d >= 1 && d <= 31 ? Optional.of(d) : Optional.empty();
    }

    // --- Botones ---

    public boolean esBoton(String accion) {
        return List.of(GUARDAR, CANCELAR, CATEGORIA, DIA, MEDIO, MONTO_NO_SE, EDITAR, CAMBIAR, FRECUENCIA, VOLVER,
                ACTUALIZAR_EXISTENTE, ES_OTRO).contains(accion);
    }

    public List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido) {
        esperas.remove(chatId);
        Alta alta = altas.get(id);
        if (alta == null) {
            return List.of(new Accion.Editar(mensajeId, "📌 Esa alta ya no está pendiente. Si querés, contame de "
                    + "nuevo qué gasto fijo agregar.", Teclado.NINGUNO));
        }
        PedidoFijo p = alta.pedido();
        Catalogo catalogo = catalogos.cargar();
        Alta nueva = switch (accion) {
            case CANCELAR -> {
                altas.remove(id);
                yield null;
            }
            case GUARDAR -> null;
            case CATEGORIA -> catalogo.categorias().stream()
                    .filter(c -> elegido != null && c.getId() == elegido.longValue()).findFirst()
                    .map(c -> alta.con(p.conCategoria(c))).orElse(alta);
            case MEDIO -> elegido == null || elegido == NO_SE
                    ? new Alta(p.conMedio(null, null), alta.sinDia(), alta.sinMonto(), true, alta.parecidoVisto())
                    : catalogo.mediosPago().stream().filter(m -> m.getId() == elegido.longValue()).findFirst()
                            .map(m -> alta.con(p.conMedio(m, catalogo.tarjetaCredito().orElse(null))))
                            .orElse(alta);
            case DIA -> {
                if (elegido != null && elegido == OTRO) {
                    yield alta;
                }
                yield elegido == null || elegido == NO_SE
                        ? new Alta(p.conDia(null), true, alta.sinMonto(), alta.sinMedio(), alta.parecidoVisto())
                        : new Alta(p.conDia(elegido.intValue()), false, alta.sinMonto(), alta.sinMedio(),
                                alta.parecidoVisto());
            }
            case MONTO_NO_SE -> new Alta(p.conMonto(null), alta.sinDia(), true, alta.sinMedio(),
                    alta.parecidoVisto());
            case FRECUENCIA -> alta.con(p.conBimestral(!p.bimestral()));
            case ES_OTRO, VOLVER, EDITAR, CAMBIAR -> new Alta(p, alta.sinDia(), alta.sinMonto(), alta.sinMedio(),
                    true);
            case ACTUALIZAR_EXISTENTE -> null;
            default -> alta;
        };
        return switch (accion) {
            case CANCELAR -> List.of(new Accion.Editar(mensajeId, "❌ <b>Cancelado</b>\n" + html(p.nombre())
                    + ": no se agregó como gasto fijo.", Teclado.NINGUNO));
            case GUARDAR -> guardar(mensajeId, id, alta);
            case ACTUALIZAR_EXISTENTE -> actualizarExistente(mensajeId, id, alta, elegido);
            case EDITAR -> {
                altas.put(id, nueva);
                yield List.of(new Accion.Editar(mensajeId, textoDeAlta(nueva) + "\n\n✏️ <b>¿Qué querés cambiar?</b>",
                        tecladoDeEdicion(id, p)));
            }
            case CAMBIAR -> {
                altas.put(id, nueva);
                Dato dato = elegido == null || elegido < 0 || elegido >= Dato.values().length ? Dato.MONTO
                        : Dato.values()[elegido.intValue()];
                Pantalla pantalla = pregunta(chatId, id, nueva, dato, true);
                yield List.of(new Accion.Editar(mensajeId, pantalla.texto(), pantalla.teclado()));
            }
            case DIA -> {
                altas.put(id, nueva);
                Pantalla pantalla;
                if (elegido != null && elegido == OTRO) {
                    esperas.put(chatId, new Espera(id, Dato.DIA));
                    pantalla = new Pantalla(textoDeAlta(nueva) + "\n\n📅 <b>¿Qué día del mes se paga?</b> "
                            + "Escribime el número.", tecladoSoloCancelar(id));
                } else {
                    pantalla = siguientePaso(chatId, id, nueva);
                }
                yield List.of(new Accion.Editar(mensajeId, pantalla.texto(), pantalla.teclado()));
            }
            default -> {
                altas.put(id, nueva);
                Pantalla pantalla = siguientePaso(chatId, id, nueva);
                yield List.of(new Accion.Editar(mensajeId, pantalla.texto(), pantalla.teclado()));
            }
        };
    }

    private List<Accion> guardar(int mensajeId, long id, Alta alta) {
        PedidoFijo p = alta.pedido();
        if (p.categoria() == null) {
            Pantalla pantalla = siguientePaso(0, id, alta);
            return List.of(new Accion.Editar(mensajeId, pantalla.texto(), pantalla.teclado()),
                    new Accion.Avisar("Antes de guardar falta la categoría"));
        }
        altas.remove(id);
        VistaFijo f = fijos.alta(new GastoFijoService.NuevoFijo(p.nombre(), p.monto(), p.categoria(), p.medioPago(),
                p.cuenta(), p.tarjeta(), p.diaDelMes(), p.bimestral() ? Frecuencia.BIMESTRAL : Frecuencia.MENSUAL,
                p.variable(), p.alias(), true));
        StringBuilder sb = new StringBuilder("✅ <b>Gasto fijo guardado</b>\n").append(html(f.nombre())).append(": ")
                .append(f.montoEsperado() == null ? "sin monto de referencia"
                        : "≈ " + formato.pesos(f.montoEsperado()))
                .append(f.frecuencia() == Frecuencia.BIMESTRAL ? " cada dos meses" : " por mes");
        if (f.diaDelMes() == null) {
            sb.append(".\n📅 Sin día: lo vas a ver en el checklist del mes, sin recordatorio de vencimiento.");
        } else {
            sb.append(", el día ").append(f.diaDelMes()).append(".");
            if (f.proximoCobro() != null) {
                sb.append("\n📅 Primer cobro: ").append(DIA_DE_SEMANA.format(f.proximoCobro())).append('.');
            }
            sb.append(f.proponerMonto() ? " Ese día te pregunto si se cobró."
                    : " Cuando lo pagues, contámelo y lo tildo.");
        }
        return List.of(new Accion.Editar(mensajeId, sb.toString(), Teclado.NINGUNO),
                new Accion.Avisar("✅ Guardado"));
    }

    private List<Accion> actualizarExistente(int mensajeId, long id, Alta alta, Long fijoId) {
        if (fijoId == null || alta.pedido().monto() == null) {
            return List.of(new Accion.Avisar("No sé qué monto ponerle"));
        }
        altas.remove(id);
        VistaFijo f = fijos.actualizarMonto(fijoId, alta.pedido().monto());
        return List.of(new Accion.Editar(mensajeId, "✅ <b>" + html(f.nombre()) + "</b> ahora es de ≈ "
                + formato.pesos(f.montoEsperado()) + ".", Teclado.NINGUNO), new Accion.Avisar("✅ Actualizado"));
    }

    // --- Pantallas ---

    record Pantalla(String texto, Teclado teclado) {
    }

    /** Lo próximo que falta (de a una cosa), o la tarjeta para guardar. */
    Pantalla siguientePaso(long chatId, long id, Alta alta) {
        PedidoFijo p = alta.pedido();
        if (!alta.parecidoVisto()) {
            Optional<VistaFijo> parecido = fijos.parecido(p.nombre(), p.alias());
            if (parecido.isPresent()) {
                return parecido(id, alta, parecido.get());
            }
            alta = new Alta(p, alta.sinDia(), alta.sinMonto(), alta.sinMedio(), true);
            altas.put(id, alta);
        }
        if (p.monto() == null && !alta.sinMonto()) {
            return pregunta(chatId, id, alta, Dato.MONTO, false);
        }
        if (p.categoria() == null) {
            return pregunta(chatId, id, alta, Dato.CATEGORIA, false);
        }
        if (p.medioPago() == null && p.tarjeta() == null && p.cuenta() == null && !alta.sinMedio()) {
            return pregunta(chatId, id, alta, Dato.MEDIO, false);
        }
        if (p.diaDelMes() == null && !alta.sinDia()) {
            return pregunta(chatId, id, alta, Dato.DIA, false);
        }
        return new Pantalla(textoDeAlta(alta), Teclado.constructor()
                .fila(new Teclado.Boton("✅ Guardar", GUARDAR + ":" + id),
                        new Teclado.Boton("✏️ Editar", EDITAR + ":" + id),
                        new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id))
                .build());
    }

    private Pantalla parecido(long id, Alta alta, VistaFijo existente) {
        PedidoFijo p = alta.pedido();
        String monto = existente.montoEsperado() == null ? "" : " (" + formato.pesos(existente.montoEsperado()) + ")";
        Teclado.Constructor t = Teclado.constructor();
        if (p.monto() != null) {
            t.fila(new Teclado.Boton("🔄 Actualizar a " + formato.pesos(p.monto()),
                    ACTUALIZAR_EXISTENTE + ":" + id + ":" + existente.id()));
        }
        t.fila(new Teclado.Boton("➕ Es otro", ES_OTRO + ":" + id),
                new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id));
        return new Pantalla(textoDeAlta(alta) + "\n\n⚠️ Ya tenés el fijo <b>" + html(existente.nombre()) + "</b>"
                + monto + ". " + (p.monto() != null ? "¿Actualizo el monto o es otro?" : "¿Es otro?"), t.build());
    }

    /** La tarjeta con una pregunta abajo y sus botones. */
    private Pantalla pregunta(long chatId, long id, Alta alta, Dato dato, boolean editando) {
        Teclado.Constructor t = Teclado.constructor();
        String pregunta;
        switch (dato) {
            case MONTO -> {
                esperas.put(chatId, new Espera(id, Dato.MONTO));
                pregunta = "💰 <b>¿Cuánto se paga, más o menos?</b> Escribímelo o decímelo en un audio.";
                t.fila(new Teclado.Boton("🤷 No sé", MONTO_NO_SE + ":" + id));
            }
            case NOMBRE -> {
                esperas.put(chatId, new Espera(id, Dato.NOMBRE));
                pregunta = "📝 <b>¿Cómo lo llamo?</b> Escribime el nombre.";
            }
            case CATEGORIA -> {
                pregunta = "🏷 <b>¿Qué categoría?</b>";
                List<Categoria> opciones = editando || alta.pedido().candidatas().isEmpty()
                        ? catalogos.cargar().categorias() : alta.pedido().candidatas();
                t.grilla(opciones.stream().map(c -> new Teclado.Boton(c.getNombreConEmoji(),
                        CATEGORIA + ":" + id + ":" + c.getId())).toList(), 2);
            }
            case MEDIO -> {
                pregunta = "💳 <b>¿Cómo se paga?</b>";
                List<Teclado.Boton> botones = new ArrayList<>();
                for (MedioPago m : catalogos.cargar().mediosPago()) {
                    botones.add(new Teclado.Boton(m.getNombre(), MEDIO + ":" + id + ":" + m.getId()));
                }
                botones.add(new Teclado.Boton("🤷 No sé", MEDIO + ":" + id + ":" + NO_SE));
                t.grilla(botones, 3);
            }
            default -> {
                pregunta = "📅 <b>¿Qué día del mes se paga?</b>";
                List<Teclado.Boton> dias = new ArrayList<>(DIAS_FRECUENTES.stream()
                        .map(d -> new Teclado.Boton(String.valueOf(d), DIA + ":" + id + ":" + d)).toList());
                t.grilla(dias, 6);
                t.fila(new Teclado.Boton("✏️ Otro", DIA + ":" + id + ":" + OTRO),
                        new Teclado.Boton("🤷 No sé", DIA + ":" + id + ":" + NO_SE));
            }
        }
        if (editando) {
            t.fila(new Teclado.Boton("↩️ Volver", VOLVER + ":" + id));
        } else {
            t.fila(new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id));
        }
        return new Pantalla(textoDeAlta(alta) + "\n\n" + pregunta, t.build());
    }

    private static Teclado tecladoSoloCancelar(long id) {
        return Teclado.constructor().fila(new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + id)).build();
    }

    private static Teclado tecladoDeEdicion(long id, PedidoFijo p) {
        return Teclado.constructor()
                .fila(cambiar("💰 Monto", id, Dato.MONTO), cambiar("📅 Día", id, Dato.DIA),
                        cambiar("📝 Nombre", id, Dato.NOMBRE))
                .fila(cambiar("🏷 Categoría", id, Dato.CATEGORIA), cambiar("💳 Medio de pago", id, Dato.MEDIO))
                .fila(new Teclado.Boton(p.bimestral() ? "🔁 Pasar a mensual" : "🔁 Pasar a cada dos meses",
                        FRECUENCIA + ":" + id), new Teclado.Boton("↩️ Volver", VOLVER + ":" + id))
                .build();
    }

    private static Teclado.Boton cambiar(String texto, long id, Dato dato) {
        return new Teclado.Boton(texto, CAMBIAR + ":" + id + ":" + dato.ordinal());
    }

    /**
     * <pre>
     * 📌 Nuevo gasto fijo
     * Seguro moto · ≈ $32.000 · mensual
     * 💳 Tarjeta de crédito · 🏷 Seguros
     * 📅 Día: sin definir
     * </pre>
     */
    String textoDeAlta(Alta alta) {
        PedidoFijo p = alta.pedido();
        StringBuilder sb = new StringBuilder("📌 <b>Nuevo gasto fijo</b>\n").append(html(p.nombre())).append(" · ")
                .append(p.monto() == null ? (alta.sinMonto() ? "sin monto" : "monto sin definir")
                        : "≈ " + formato.pesos(p.monto()))
                .append(" · ").append(p.bimestral() ? "cada dos meses" : "mensual");
        sb.append("\n💳 ").append(p.medioPago() == null && p.cuenta() == null ? "Medio sin definir"
                : html(FormatoMensajes.medio(p.medioPago(), p.cuenta())));
        sb.append(" · ").append(p.categoria() == null ? "🏷 Categoría sin definir"
                : html(p.categoria().getNombreConEmoji()));
        if (p.diaDelMes() == null) {
            sb.append("\n📅 Día: sin definir");
        } else {
            LocalDate primero = fijos.primerCobro(p.diaDelMes());
            sb.append("\n📅 Día ").append(p.diaDelMes()).append(" · primer cobro: ")
                    .append(DIA_DE_SEMANA.format(primero));
        }
        if (p.variable()) {
            sb.append("\n📈 Monto variable: cada mes te propongo el promedio de los últimos cobros.");
        }
        return sb.toString();
    }
}
