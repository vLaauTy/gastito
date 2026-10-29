package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.ticket.ControlesTicket;

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
    /** Un sueldo pendiente: cambiar su período entre el mes anterior al cobro y el mes del cobro. */
    static final String PERIODO = "pp";
    /** Compras repetidas: unir el nuevo con el que ya estaba, o guardar los dos. */
    static final String UNIR = "ru";
    static final String DE_NUEVO = "rd";
    /** Juntadas: nombres, la parte de alguien, dar por perdido, armar el mensaje y volver a la tarjeta. */
    static final String JUNTADA_NOMBRES = "jn";
    static final String JUNTADA_PARTE = "jp";
    static final String JUNTADA_PERDIDO = "jx";
    static final String JUNTADA_MENSAJE = "jm";
    static final String JUNTADA_VER = "jv";
    /** Una devolución: de quién es ("jd:devolución:persona"). */
    static final String DEVOLUCION_DE = "jd";
    /** Pagó menos de lo que debía: queda debiendo la diferencia, o se da por saldado. */
    static final String DEVOLUCION_QUEDA_DEBIENDO = "jq";
    static final String DEVOLUCION_SALDADO = "js";
    /** Un gasto que se guardó solo: deshacerlo (se borra) o elegir el medio de pago con un toque. */
    static final String DESHACER = "du";
    static final String MEDIO_RAPIDO = "gm";
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

    /** De dónde salen los datos de una juntada (el total, las partes); sin juntadas, ninguna. */
    private Function<Long, Optional<VistaJuntada>> juntadas = id -> Optional.empty();

    @Autowired(required = false)
    void usarJuntadas(JuntadaService servicio) {
        usarJuntadas(servicio::vista);
    }

    void usarJuntadas(Function<Long, Optional<VistaJuntada>> fuente) {
        this.juntadas = fuente;
    }

    /** Cómo se compara un sueldo cobrado con el cálculo del mes; sin calculadora, no se compara. */
    private Function<Movimiento, Optional<String>> comparacionDeSueldo = m -> Optional.empty();

    @Autowired(required = false)
    void usarSueldo(SueldoService servicio) {
        usarSueldo(m -> servicio.comparar(m.periodoOFecha(), m.getMonto())
                .map(c -> "🧮 " + TarjetasRecibo.textoComparacion(c, formato, true)));
    }

    void usarSueldo(Function<Movimiento, Optional<String>> fuente) {
        this.comparacionDeSueldo = fuente;
    }

    private Optional<VistaJuntada> juntada(Movimiento m) {
        return m.isJuntada() && m.getId() != null && m.getTipo() == TipoMovimiento.GASTO ? juntadas.apply(m.getId())
                : Optional.empty();
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

    /** Pendiente (o guardado solo) esperando que el usuario escriba el valor nuevo de un campo. */
    public String pidiendo(Movimiento m, Campo campo) {
        return actual(m) + "\n\n" + campo.pedido;
    }

    /**
     * Pendiente con el valor que se entendió de un audio para el campo que se estaba editando, antes de aplicarlo.
     *
     * @param valor el valor ya formateado ("$3.700", "Ayer 21:30")
     */
    public String propuesta(Movimiento m, String escuchado, Campo campo, String valor) {
        return actual(m) + "\n\n🎙 Escuché: \"" + html(escuchado) + "\" → <b>" + campo.etiqueta + ": "
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

    /**
     * Un gasto que se guardó solo, en dos renglones: "✅ Guardado · $3.700 · 🍬 Kiosco · 💵 Efectivo" y la
     * descripción (de un ticket, con cuántos productos).
     */
    public String guardadoAutomatico(Movimiento m) {
        Categoria c = m.getCategoria();
        String linea = "✅ <b>Guardado</b> · <b>" + formato.pesos(m.getMonto()) + "</b> · "
                + (c == null ? "🏷 Sin categoría" : html(c.getNombreConEmoji())) + " · " + medioCorto(m);
        String detalle = m.getDescripcion() == null || m.getDescripcion().isBlank() ? "" : html(m.getDescripcion());
        long productos = m.getItems().stream().filter(i -> !i.isDescuento()).count();
        if (productos > 0) {
            detalle += (detalle.isEmpty() ? "" : " · ") + productos + (productos == 1 ? " producto" : " productos");
        }
        return escuchado(m) + linea + (detalle.isEmpty() ? "" : "\n" + detalle);
    }

    /** "💵 Efectivo", "💳 Débito (Galicia)" o "💳 <i>Sin especificar</i>". */
    private static String medioCorto(Movimiento m) {
        if (m.getMedioPago() == null && m.getCuenta() == null) {
            return "💳 <i>Sin especificar</i>";
        }
        boolean efectivo = m.getMedioPago() != null && m.getMedioPago().isEfectivo();
        return (efectivo ? "💵 " : "💳 ") + html(FormatoMensajes.medio(m.getMedioPago(), m.getCuenta()));
    }

    /** ↩️ Deshacer: el gasto se borró. */
    public String deshecho(Movimiento m) {
        Categoria c = m.getCategoria();
        return "↩️ <b>Deshecho</b> · <s>" + formato.pesos(m.getMonto()) + "</s>"
                + (c == null ? "" : " · " + html(c.getNombreConEmoji()))
                + (m.getDescripcion() == null ? "" : "\n" + html(m.getDescripcion()))
                + "\nNo quedó guardado.";
    }

    /** La tarjeta de un gasto como está ahora: pendiente, o guardado solo (con los datos en el lugar). */
    public String actual(Movimiento m) {
        return m.getEstado() == EstadoMovimiento.CONFIRMADO ? guardadoAutomatico(m) : pendiente(m);
    }

    public String cancelado(Movimiento m) {
        return conTitulo(m, "❌ <b>Cancelado</b>");
    }

    // --- Compras repetidas ---

    /** El nuevo y el que ya estaba, lado a lado, para decir si son la misma compra. */
    public String repetido(Movimiento existente, Movimiento nuevo) {
        boolean cobroDeFijo = existente.getGastoFijoId() != null && existente.isPendiente();
        return "🔁 <b>¿Ya lo tenías registrado?</b>\n"
                + (cobroDeFijo ? "Se parece al cobro de un gasto fijo que estaba esperando:"
                        : "Encontré un gasto muy parecido:")
                + "\n\n<b>" + (cobroDeFijo ? "Cobro esperado" : "Ya registrado") + "</b> · " + origen(existente)
                + "\n" + resumenCorto(existente)
                + "\n\n<b>Nuevo</b> · " + origen(nuevo) + "\n" + resumenCorto(nuevo);
    }

    /** Cómo queda la tarjeta después de unir los dos. */
    public String unidos(Movimiento m) {
        return conTitulo(m, "🔗 <b>Unidos: quedó uno solo</b>");
    }

    private String resumenCorto(Movimiento m) {
        Categoria c = m.getCategoria();
        String linea = "💰 <b>" + formato.pesos(m.getMonto()) + "</b>"
                + (m.getDescripcion() == null ? "" : " · " + html(m.getDescripcion()))
                + "\n" + (c == null ? "🏷 Sin categoría" : html(c.getNombreConEmoji()))
                + " · 💳 " + html(FormatoMensajes.medio(m.getMedioPago(), m.getCuenta()))
                + "\n📅 " + formato.fecha(m.getFechaHora());
        int productos = (int) m.getItems().stream().filter(i -> !i.isDescuento()).count();
        return productos == 0 ? linea : linea + " · " + productos + (productos == 1 ? " producto" : " productos");
    }

    private static String origen(Movimiento m) {
        return switch (m.getOrigen()) {
            case TICKET -> "📷 ticket";
            case RECIBO -> "💼 recibo";
            case AUDIO -> "🎙 audio";
            case AVISO -> "🔔 aviso";
            case DASHBOARD -> "🖥 dashboard";
            case AUTOMATICO -> m.getGastoFijoId() != null ? "📌 gasto fijo" : "⚙️ automático";
            case TEXTO, RESUMEN -> "✍️ texto";
        };
    }

    /** "Es la misma" (ru:nuevo:existente) o "Compré de nuevo" (rd:nuevo). */
    public Teclado repetido(long nuevoId, long existenteId) {
        return Teclado.constructor()
                .fila(new Teclado.Boton("🔗 Es la misma: uní los datos", UNIR + ":" + nuevoId + ":" + existenteId))
                .fila(new Teclado.Boton("➕ Compré de nuevo: guardá las dos", DE_NUEVO + ":" + nuevoId))
                .build();
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
        if (m.getTipo() == TipoMovimiento.DEVOLUCION) {
            return "💰 <b>" + formato.pesos(m.getMonto()) + "</b>\n📝 " + html(m.getDescripcion()) + "\n📅 "
                    + formato.fecha(m.getFechaHora());
        }
        List<String> lineas = new ArrayList<>();
        Optional<VistaJuntada> juntada = juntada(m);
        if (juntada.isPresent()) {
            VistaJuntada j = juntada.get();
            lineas.add("💸 Pagaste <b>" + formato.pesos(j.totalPagado()) + "</b>"
                    + (j.billetera() == null ? "" : " · " + html(j.billetera())));
            lineas.add("🙋 Tu gasto real <b>" + formato.pesos(j.miParte()) + "</b>");
            lineas.add(j.aCobrar().signum() > 0 ? "📥 A cobrar <b>" + formato.pesos(j.aCobrar()) + "</b>"
                    : "📥 Ya no te deben nada");
        } else {
            lineas.add(m.getMonto() == null ? "💰 <i>Falta el monto</i>"
                    : "💰 <b>" + formato.pesos(m.getMonto()) + "</b>");
        }
        if (m.getDescripcion() != null && !m.getDescripcion().isBlank()) {
            lineas.add("📝 " + html(m.getDescripcion()));
        }
        if (m.getCuotaTotal() != null) {
            lineas.add("🗂 Cuota " + m.getCuotaActual() + " de " + m.getCuotaTotal() + " · total "
                    + formato.pesos(m.getMonto().multiply(BigDecimal.valueOf(m.getCuotaTotal()))));
        }
        Categoria c = m.getCategoria();
        lineas.add(c == null ? "🏷 <b>¿Qué categoría?</b>"
                : (c.getEmoji() == null ? "🏷" : c.getEmoji()) + " " + html(c.getNombre()));
        lineas.add("💳 " + html(FormatoMensajes.medio(m.getMedioPago(), m.getCuenta())));
        lineas.add("📅 " + formato.fecha(m.getFechaHora()));
        juntada.ifPresent(j -> lineas.add("\n" + personas(j)));
        return String.join("\n", lineas);
    }

    /** "👥 Entre 6:" y cada uno con lo que pone y si ya pagó. */
    String personas(VistaJuntada j) {
        StringBuilder sb = new StringBuilder("👥 <b>Entre " + j.personas() + "</b> (vos " + formato.pesos(j.miParte())
                + ")");
        for (VistaJuntada.Persona p : j.participantes()) {
            boolean pagoAlgo = p.pagado().signum() > 0 && p.pagado().compareTo(p.monto()) < 0;
            sb.append("\n• ").append(html(p.nombre())).append(": ").append(formato.pesos(p.monto())).append(' ')
                    .append(switch (p.estado()) {
                        case PENDIENTE -> pagoAlgo ? "⏳ (pagó " + formato.pesos(p.pagado()) + ", debe "
                                + formato.pesos(p.restante()) + ")" : "⏳";
                        case PAGADO -> pagoAlgo ? "✅ saldado con " + formato.pesos(p.pagado()) : "✅";
                        case PERDIDO -> "🚫 perdido";
                    });
        }
        return sb.toString();
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

    /**
     * Las cuentas del ticket que no cierran y, si las hay, las líneas que la IA no pudo leer (ticket arrugado).
     * Solo mientras está pendiente: después de guardar ya no hay nada que revisar.
     */
    private String avisoDeSuma(Movimiento m) {
        if (m.getMonto() == null) {
            return "";
        }
        List<ControlesTicket.Diferencia> diferencias = ControlesTicket.revisar(m);
        if (diferencias.isEmpty()) {
            return "";
        }
        StringBuilder aviso = new StringBuilder("\n");
        for (ControlesTicket.Diferencia d : diferencias) {
            aviso.append("\n⚠️ ").append(diferencia(d));
        }
        for (ControlesTicket.RenglonDudoso r : ControlesTicket.renglonesDudosos(m)) {
            aviso.append("\n⚠️ ").append(renglonDudoso(r));
        }
        List<String> ilegibles = m.getLineasIlegibles();
        if (!ilegibles.isEmpty()) {
            aviso.append("\n🔍 No pude leer bien:");
            ilegibles.forEach(linea -> aviso.append("\n• ").append(html(linea)));
        }
        return aviso.append("\nRevisalo antes de guardar.").toString();
    }

    /** "Pasta mac &amp; cheese: 1 × $4.488 da $4.488, pero el renglón dice $4.463,90." */
    String renglonDudoso(ControlesTicket.RenglonDudoso r) {
        String cantidad = r.cantidad().stripTrailingZeros().toPlainString().replace('.', ',');
        return html(r.descripcion()) + ": " + cantidad + " × " + formato.pesos(r.precioUnitario()) + " da <b>"
                + formato.pesos(r.calculado()) + "</b>, pero el renglón dice <b>" + formato.pesos(r.impreso())
                + "</b>.";
    }

    /** "Los productos suman $38.133 y el subtotal dice $38.333." Los descuentos, en positivo. */
    String diferencia(ControlesTicket.Diferencia d) {
        String calculado = "<b>" + formato.pesos(d.calculado().abs()) + "</b>";
        String impreso = "<b>" + formato.pesos(d.impreso().abs()) + "</b>";
        return switch (d.control()) {
            case PRODUCTOS_Y_SUBTOTAL -> "Los productos suman " + calculado + " y el subtotal dice " + impreso + ".";
            case DESCUENTOS -> "Los descuentos suman " + calculado + " y el ticket dice " + impreso + ".";
            case SUBTOTAL_Y_TOTAL -> "El subtotal menos los descuentos da " + calculado + " y el total dice "
                    + impreso + ".";
            case PRODUCTOS_Y_TOTAL -> "Los productos suman " + calculado + " y el total dice " + impreso + ".";
        };
    }


    /** "Sueldo de octubre · cobrado el 05/11": el mes trabajado y cuándo se cobró. */
    private String claseConPeriodo(Movimiento m) {
        YearMonth periodo = m.getPeriodo();
        if (periodo == null) {
            return m.getClaseIngreso().getNombre();
        }
        String mes = periodo.getMonth().getDisplayName(java.time.format.TextStyle.FULL,
                java.util.Locale.forLanguageTag("es-AR"));
        return m.getClaseIngreso().getNombre() + " de " + mes + (periodo.getYear() == m.getFechaHora().getYear()
                ? "" : " " + periodo.getYear()) + " · cobrado el "
                + java.time.format.DateTimeFormatter.ofPattern("dd/MM").format(m.getFechaHora());
    }
    private String datosIngreso(Movimiento m) {
        List<String> lineas = new ArrayList<>();
        lineas.add(m.getMonto() == null ? "💰 <b>¿Cuánto?</b>" : "💰 <b>" + formato.pesos(m.getMonto()) + "</b>");
        lineas.add("🏷 " + claseConPeriodo(m));
        if (m.getDescripcion() != null && !m.getDescripcion().isBlank()) {
            lineas.add("📝 " + html(m.getDescripcion()));
        }
        String destino = m.getCuenta() != null ? html(m.getCuenta().getNombre())
                : m.getMedioPago() != null && m.getMedioPago().isEfectivo() ? "Efectivo"
                : "<i>Sin especificar</i>";
        lineas.add("🏦 " + destino);
        lineas.add("📅 " + formato.fecha(m.getFechaHora()));
        if (m.getClaseIngreso() == ClaseIngreso.SUELDO && m.getMonto() != null) {
            comparacionDeSueldo.apply(m).ifPresent(lineas::add);
        }
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
            if (m.getClaseIngreso() == ClaseIngreso.SUELDO && m.getPeriodo() != null) {
                t.fila(boton("📆 Cambiar el mes del sueldo", PERIODO, id));
            }
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
        if (juntada(m).isPresent()) {
            t.fila(boton("👥 Nombres", JUNTADA_NOMBRES, id), boton("💲 La parte de alguien", JUNTADA_PARTE, id));
        }
        return t.build();
    }

    /**
     * Un gasto que se guardó solo: ✏️ Editar y ↩️ Deshacer. Si no se sabe con qué se pagó, arriba una fila para
     * elegirlo con un toque.
     *
     * @param medios los medios de pago del catálogo, para la fila rápida
     */
    public Teclado guardadoAutomatico(Movimiento m, List<MedioPago> medios) {
        long id = m.getId();
        Teclado.Constructor t = Teclado.constructor();
        if (m.getMedioPago() == null && m.getCuenta() == null && !medios.isEmpty()) {
            t.fila(medios.stream().map(mp -> boton(medioRapido(mp), MEDIO_RAPIDO, id, mp.getId()))
                    .toArray(Teclado.Boton[]::new));
        }
        return t.fila(boton("✏️ Editar", EDITAR, id), boton("↩️ Deshacer", DESHACER, id)).build();
    }

    /** El nombre corto de un medio para la fila rápida: "💵 Efectivo", "💳 Débito", "💳 Crédito", "🏦 Transf.", "QR". */
    static String medioRapido(MedioPago mp) {
        String nombre = mp.getNombre();
        String minusculas = nombre.toLowerCase(java.util.Locale.ROOT);
        if (mp.isEfectivo()) {
            return "💵 " + nombre;
        }
        if (mp.esTarjetaDeCredito()) {
            return "💳 Crédito";
        }
        if (minusculas.startsWith("transf")) {
            return "🏦 Transf.";
        }
        return minusculas.startsWith("déb") || minusculas.startsWith("deb") ? "💳 " + nombre : nombre;
    }

    // --- Juntadas ---

    /** Una juntada guardada: lo que se puede hacer mientras alguien debe. Si ya no debe nadie, nada. */
    public Teclado juntadaGuardada(VistaJuntada j) {
        if (j.pendientes().isEmpty()) {
            return Teclado.NINGUNO;
        }
        long id = j.movimientoId();
        return Teclado.constructor()
                .fila(boton("💬 Armar mensaje", JUNTADA_MENSAJE, id), boton("👥 Nombres", JUNTADA_NOMBRES, id))
                .fila(boton("💲 La parte de alguien", JUNTADA_PARTE, id),
                        boton("🚫 Dar por perdido", JUNTADA_PERDIDO, id))
                .build();
    }

    /**
     * Para elegir a quién se renombra, a quién se le cambia la parte o qué parte se da por perdida. Para cambiar la
     * parte o darla por perdida, solo los que todavía deben.
     */
    public Teclado elegirPersona(String accion, VistaJuntada j) {
        long id = j.movimientoId();
        Teclado.Constructor t = Teclado.constructor();
        List<VistaJuntada.Persona> personas = accion.equals(JUNTADA_NOMBRES) ? j.participantes() : j.pendientes();
        for (VistaJuntada.Persona p : personas) {
            // Para dar por perdido importa lo que todavía debe; para nombres y partes, su parte.
            BigDecimal monto = accion.equals(JUNTADA_PERDIDO) ? p.restante() : p.monto();
            t.fila(boton(p.nombre() + " · " + formato.pesos(monto), accion, id, p.id()));
        }
        t.fila(boton("↩️ Volver", JUNTADA_VER, id));
        return t.build();
    }

    /** "¿Qué hacemos con esta juntada?": el texto de la tarjeta y lo que se pide arriba de los botones. */
    public String juntadaPidiendo(Movimiento m, String pedido) {
        return terminadoOPendiente(m) + "\n\n" + pedido;
    }

    /** La tarjeta de la juntada como está ahora: pendiente o guardada. */
    public String terminadoOPendiente(Movimiento m) {
        return m.isPendiente() ? pendiente(m) : conTitulo(m, "✅ <b>Guardado</b>");
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
