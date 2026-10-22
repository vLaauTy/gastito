package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;

/**
 * La tarjeta de un gasto: un mensaje con un dato por línea que se va editando en el lugar
 * (elegir categoría, editar un campo, guardar o cancelar), y los botones de cada estado.
 */
@Component
public class Tarjetas {

    // Datos de los botones: "<acción>:<id del movimiento>[:<id elegido>]". Telegram admite hasta 64 bytes.
    static final String GUARDAR = "g";
    static final String CANCELAR = "c";
    static final String EDITAR = "e";
    static final String VOLVER = "v";
    static final String OTRA_CATEGORIA = "o";
    static final String CATEGORIA = "k";
    static final String MEDIOS = "mp";
    static final String MEDIO = "p";
    static final String EDITAR_MONTO = "em";
    static final String EDITAR_FECHA = "ef";
    static final String EDITAR_DESCRIPCION = "ed";
    static final String APLICAR = "a";
    /** Id de medio de pago que significa "sin medio de pago". */
    static final long SIN_MEDIO = 0;

    /** Campos que se editan escribiendo (o diciendo en un audio) el valor nuevo. */
    public enum Campo {
        MONTO("Monto", "✏️ <b>Escribí el nuevo monto</b>\n<i>Por ejemplo: 3700 o 1.500,50</i>"),
        FECHA("Fecha", "✏️ <b>Escribí la nueva fecha</b>\n<i>Por ejemplo: ayer, el sábado, 12/10 o 12/10 21:30</i>"),
        DESCRIPCION("Descripción", "✏️ <b>Escribí la nueva descripción</b>");

        private final String etiqueta;
        private final String pedido;

        Campo(String etiqueta, String pedido) {
            this.etiqueta = etiqueta;
            this.pedido = pedido;
        }
    }

    private final FormatoMensajes formato;

    public Tarjetas(FormatoMensajes formato) {
        this.formato = formato;
    }

    // --- Textos ---

    /** La tarjeta de un gasto (o un ingreso) pendiente. */
    public String pendiente(Movimiento m) {
        return escuchado(m) + (m.isIngreso() ? "💵 <b>Nuevo ingreso</b>\n" : "🧾 <b>Nuevo gasto</b>\n") + datos(m);
    }

    /** Pendiente sin monto, recién interpretado: se espera que el usuario lo escriba. */
    public String pidiendoMontoFaltante(Movimiento m) {
        return pendiente(m) + "\n\n✏️ <b>¿Cuánto fue?</b> Escribime el monto.";
    }

    /** Pendiente esperando que el usuario escriba el valor nuevo de un campo. */
    public String pidiendo(Movimiento m, Campo campo) {
        return pendiente(m) + "\n\n" + campo.pedido;
    }

    /**
     * Pendiente con el valor que se entendió de un audio para el campo que se estaba editando, antes de aplicarlo.
     *
     * @param valor el valor ya formateado ("$3.700", "Ayer 21:30")
     */
    public String propuesta(Movimiento m, String escuchado, Campo campo, String valor) {
        return pendiente(m) + "\n\n🎙 Escuché: \"" + html(escuchado) + "\" → <b>" + campo.etiqueta + ": "
                + html(valor) + "</b>";
    }

    /** @param totalMes de un gasto, el total del mes en su categoría; de un ingreso, el de todos los ingresos */
    public String guardado(Movimiento m, BigDecimal totalMes) {
        String mes = formato.mes(YearMonth.from(m.getFechaHora()));
        return escuchado(m) + "✅ <b>Guardado</b>\n" + datos(m) + "\n\n📊 En " + mes
                + (m.isIngreso()
                        ? " cobraste <b>" + formato.pesos(totalMes) + "</b> en total."
                        : " llevás <b>" + formato.pesos(totalMes) + "</b> en "
                                + html(m.getCategoria().getNombreConEmoji()) + ".");
    }

    public String cancelado(Movimiento m) {
        return escuchado(m) + "❌ <b>Cancelado</b>\n" + datos(m);
    }

    /** Cómo queda la tarjeta de un gasto que ya no está pendiente. */
    public String terminado(Movimiento m) {
        return m.getEstado() == EstadoMovimiento.CONFIRMADO
                ? escuchado(m) + "✅ <b>Guardado</b>\n" + datos(m)
                : cancelado(m);
    }

    /** Si el gasto vino de un audio, lo que se escuchó, arriba de todo: así se ve si Whisper entendió mal. */
    private static String escuchado(Movimiento m) {
        if (m.getOrigen() != Origen.AUDIO || m.getTextoOriginal() == null || m.getTextoOriginal().isBlank()) {
            return "";
        }
        return "🎙 Escuché: \"" + html(m.getTextoOriginal()) + "\"\n\n";
    }

    private String datos(Movimiento m) {
        if (m.isIngreso()) {
            return datosIngreso(m);
        }
        List<String> lineas = new ArrayList<>();
        lineas.add(m.getMonto() == null ? "💰 <i>Falta el monto</i>" : "💰 <b>" + formato.pesos(m.getMonto()) + "</b>");
        if (m.getDescripcion() != null && !m.getDescripcion().isBlank()) {
            lineas.add("📝 " + html(m.getDescripcion()));
        }
        Categoria c = m.getCategoria();
        lineas.add(c == null ? "🏷 <b>¿Qué categoría?</b>"
                : (c.getEmoji() == null ? "🏷" : c.getEmoji()) + " " + html(c.getNombre()));
        lineas.add("💳 " + html(FormatoMensajes.medio(m.getMedioPago(), m.getCuenta())));
        lineas.add("📅 " + formato.fecha(m.getFechaHora()));
        return String.join("\n", lineas);
    }

    private String datosIngreso(Movimiento m) {
        List<String> lineas = new ArrayList<>();
        lineas.add("💰 <b>" + formato.pesos(m.getMonto()) + "</b>");
        lineas.add("🏷 " + m.getClaseIngreso().getNombre());
        if (m.getDescripcion() != null && !m.getDescripcion().isBlank()) {
            lineas.add("📝 " + html(m.getDescripcion()));
        }
        String destino = m.getCuenta() != null ? html(m.getCuenta().getNombre())
                : m.getMedioPago() != null && m.getMedioPago().isEfectivo() ? "Efectivo"
                : "<i>Sin especificar</i>";
        lineas.add("🏦 " + destino);
        lineas.add("📅 " + formato.fecha(m.getFechaHora()));
        return String.join("\n", lineas);
    }

    // --- Botones ---

    /**
     * Sin categoría: una fila por candidata y "Otra…". Sin monto: "Poner monto". Completo: "Guardar".
     * Siempre "Editar" y "Cancelar". Un ingreso solo tiene "Guardar" y "Cancelar": si algo está mal, se cancela
     * y se manda de nuevo.
     */
    public Teclado principal(Movimiento m) {
        long id = m.getId();
        Teclado.Constructor t = Teclado.constructor();
        if (m.isIngreso()) {
            t.fila(boton("✅ Guardar", GUARDAR, id), boton("❌ Cancelar", CANCELAR, id));
        } else if (m.getCategoria() == null) {
            for (Categoria c : m.getCandidatas()) {
                t.fila(boton(c.getNombreConEmoji(), CATEGORIA, id, c.getId()));
            }
            t.fila(boton("Otra…", OTRA_CATEGORIA, id));
            t.fila(boton("✏️ Editar", EDITAR, id), boton("❌ Cancelar", CANCELAR, id));
        } else if (m.getMonto() == null) {
            t.fila(boton("💰 Poner monto", EDITAR_MONTO, id), boton("✏️ Editar", EDITAR, id),
                    boton("❌ Cancelar", CANCELAR, id));
        } else {
            t.fila(boton("✅ Guardar", GUARDAR, id), boton("✏️ Editar", EDITAR, id),
                    boton("❌ Cancelar", CANCELAR, id));
        }
        return t.build();
    }

    public Teclado edicion(long id) {
        return Teclado.constructor()
                .fila(boton("🏷 Categoría", OTRA_CATEGORIA, id), boton("💰 Monto", EDITAR_MONTO, id))
                .fila(boton("💳 Medio de pago", MEDIOS, id), boton("📅 Fecha", EDITAR_FECHA, id))
                .fila(boton("📝 Descripción", EDITAR_DESCRIPCION, id))
                .fila(volver(id))
                .build();
    }

    public Teclado categorias(long id, List<Categoria> categorias) {
        List<Teclado.Boton> botones = categorias.stream()
                .map(c -> boton(c.getNombreConEmoji(), CATEGORIA, id, c.getId()))
                .toList();
        return Teclado.constructor().grilla(botones, 2).fila(volver(id)).build();
    }

    public Teclado medios(long id, List<MedioPago> medios) {
        List<Teclado.Boton> botones = new ArrayList<>(medios.stream()
                .map(mp -> boton(mp.getNombre(), MEDIO, id, mp.getId()))
                .toList());
        botones.add(boton("Sin medio de pago", MEDIO, id, SIN_MEDIO));
        return Teclado.constructor().grilla(botones, 2).fila(volver(id)).build();
    }

    public Teclado soloVolver(long id) {
        return Teclado.constructor().fila(volver(id)).build();
    }

    /** Para un valor entendido de un audio: aplicarlo, o salir de la edición sin cambiar nada. */
    public Teclado propuesta(long id) {
        return Teclado.constructor()
                .fila(boton("✅ Aplicar", APLICAR, id), boton("↩ Cancelar edición", VOLVER, id))
                .build();
    }

    private static Teclado.Boton volver(long id) {
        return boton("↩ Volver", VOLVER, id);
    }

    private static Teclado.Boton boton(String texto, String accion, long id) {
        return new Teclado.Boton(texto, accion + ":" + id);
    }

    private static Teclado.Boton boton(String texto, String accion, long id, long elegido) {
        return new Teclado.Boton(texto, accion + ":" + id + ":" + elegido);
    }
}
