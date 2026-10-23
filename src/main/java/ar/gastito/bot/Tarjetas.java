package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;
import ar.gastito.movimiento.Origen;
import ar.gastito.ticket.ValidadorTicket;

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

    static final int PRODUCTOS_VISIBLES = 8;
    /** Caracteres de un renglón de producto: entra en la pantalla de un celular sin cortarse. */
    static final int ANCHO_RENGLON = 30;
    /** Telegram admite 4096 caracteres; se deja lugar para lo que se agrega al editar (pedidos, avisos). */
    static final int LARGO_MAXIMO_TICKET = 3500;

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
    private final int largoMaximoTicket;

    @Autowired
    public Tarjetas(FormatoMensajes formato) {
        this(formato, LARGO_MAXIMO_TICKET);
    }

    Tarjetas(FormatoMensajes formato, int largoMaximoTicket) {
        this.formato = formato;
        this.largoMaximoTicket = largoMaximoTicket;
    }

    // --- Textos ---

    /** La tarjeta de un gasto (o un ingreso) pendiente. Un ticket avisa si los productos no suman el total. */
    public String pendiente(Movimiento m) {
        if (esTicket(m)) {
            return conTitulo(m, "🧾 <b>Ticket</b>") + avisoDeSuma(m);
        }
        return conTitulo(m, m.isIngreso() ? "💵 <b>Nuevo ingreso</b>" : "🧾 <b>Nuevo gasto</b>");
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
        return conTitulo(m, "✅ <b>Guardado</b>") + "\n\n📊 En " + mes
                + (m.isIngreso()
                        ? " cobraste <b>" + formato.pesos(totalMes) + "</b> en total."
                        : " llevás <b>" + formato.pesos(totalMes) + "</b> en "
                                + html(m.getCategoria().getNombreConEmoji()) + ".");
    }

    public String cancelado(Movimiento m) {
        return conTitulo(m, "❌ <b>Cancelado</b>");
    }

    /** Cómo queda la tarjeta de un gasto que ya no está pendiente. */
    public String terminado(Movimiento m) {
        return m.getEstado() == EstadoMovimiento.CONFIRMADO ? conTitulo(m, "✅ <b>Guardado</b>") : cancelado(m);
    }

    /**
     * El título y los datos. Un ticket lleva el comercio (la descripción) en el título; si con los productos el
     * mensaje no entra en Telegram, se muestran solo el total y cuántos productos son.
     */
    private String conTitulo(Movimiento m, String titulo) {
        if (!esTicket(m)) {
            return escuchado(m) + titulo + "\n" + datos(m);
        }
        String encabezado = titulo + " · " + html(m.getDescripcion()) + "\n";
        String completo = encabezado + datosTicket(m, true);
        return completo.length() <= largoMaximoTicket ? completo : encabezado + datosTicket(m, false);
    }

    private static boolean esTicket(Movimiento m) {
        return m.getOrigen() == Origen.TICKET;
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

    /**
     * Fecha, productos (hasta {@value #PRODUCTOS_VISIBLES} y "…y N más"), total, medio de pago y categoría.
     * Los productos van en monoespaciado, así los precios quedan alineados.
     */
    private String datosTicket(Movimiento m, boolean conProductos) {
        List<String> lineas = new ArrayList<>();
        lineas.add("📅 " + formato.fecha(m.getFechaHora()));
        List<MovimientoItem> items = m.getItems();
        if (!items.isEmpty() && conProductos) {
            StringBuilder bloque = new StringBuilder("🛒 <b>Productos</b>\n<pre>");
            for (int i = 0; i < Math.min(PRODUCTOS_VISIBLES, items.size()); i++) {
                bloque.append(html(renglon(items.get(i)))).append('\n');
            }
            if (items.size() > PRODUCTOS_VISIBLES) {
                bloque.append("…y ").append(items.size() - PRODUCTOS_VISIBLES).append(" más\n");
            }
            bloque.setLength(bloque.length() - 1);
            lineas.add(bloque.append("</pre>").toString());
        } else if (!items.isEmpty()) {
            long productos = items.stream().filter(i -> !i.isDescuento()).count();
            long descuentos = items.size() - productos;
            lineas.add("🛒 " + productos + (productos == 1 ? " producto" : " productos")
                    + (descuentos == 0 ? "" : " y " + descuentos + (descuentos == 1 ? " descuento" : " descuentos")));
        }
        lineas.add("💰 Total <b>" + formato.pesos(m.getMonto()) + "</b>");
        lineas.add("💳 " + html(FormatoMensajes.medio(m.getMedioPago(), m.getCuenta())));
        Categoria c = m.getCategoria();
        lineas.add(c == null ? "🏷 <b>¿Qué categoría?</b>"
                : (c.getEmoji() == null ? "🏷" : c.getEmoji()) + " " + html(c.getNombre()));
        return String.join("\n", lineas);
    }

    /** "Leche 1 L ×2 ....... $2.900", con el precio contra el margen derecho. */
    String renglon(MovimientoItem item) {
        BigDecimal subtotal = item.getSubtotal();
        String precio = subtotal.signum() < 0 ? "-" + formato.pesos(subtotal.negate()) : formato.pesos(subtotal);
        String nombre = item.getDescripcion() + cantidad(item.getCantidad());
        int lugar = ANCHO_RENGLON - precio.length() - 3; // dos espacios y al menos un punto
        if (nombre.length() > lugar) {
            nombre = nombre.substring(0, Math.max(1, lugar - 1)).strip() + "…";
        }
        int puntos = Math.max(1, ANCHO_RENGLON - nombre.length() - precio.length() - 2);
        return nombre + " " + ".".repeat(puntos) + " " + precio;
    }

    /** " ×2", " ×0,532"; nada si es una unidad o no figura. */
    private static String cantidad(BigDecimal cantidad) {
        if (cantidad == null || cantidad.compareTo(BigDecimal.ONE) == 0) {
            return "";
        }
        return " ×" + cantidad.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Solo mientras está pendiente: después de guardar ya no hay nada que revisar. */
    private String avisoDeSuma(Movimiento m) {
        if (m.getItems().isEmpty() || m.getMonto() == null) {
            return "";
        }
        BigDecimal suma = m.getItems().stream().map(MovimientoItem::getSubtotal).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        if (ValidadorTicket.coinciden(suma, m.getMonto())) {
            return "";
        }
        return "\n\n⚠️ Los productos suman <b>" + formato.pesos(suma) + "</b> y el total dice <b>"
                + formato.pesos(m.getMonto()) + "</b>. Revisalo antes de guardar.";
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
