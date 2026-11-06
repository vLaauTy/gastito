package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.interpretacion.PedidoPrestamo;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.prestamo.PrestamoInvalidoException;
import ar.gastito.prestamo.PrestamoService;
import ar.gastito.prestamo.VistaPrestamo;

/**
 * Los préstamos en el chat: el préstamo cargado junto con un gasto ("30.000 son de Martín…") o solo, el cobro de las
 * cuotas ("Martín me pasó la cuota"), el mensaje para copiarle a la persona y el aviso de una cuota atrasada. El bot
 * no le escribe a nadie más que al usuario.
 */
@Component
public class ConversacionPrestamos {

    /** Guardar un préstamo cargado solo: "pg:<préstamo>". */
    static final String GUARDAR = "pg";
    /** Cancelar un préstamo cargado solo: "px:<préstamo>". */
    static final String CANCELAR = "px";
    /** La devolución es de este préstamo: "pc:<devolución>:<préstamo>". */
    static final String COBRAR = "pc";
    /** "✅ Me pasó la cuota" (la próxima, a la cuenta del préstamo): "pq:<préstamo>". */
    static final String COBRAR_PROXIMA = "pq";
    /** "💬 Armar mensaje": "pm:<préstamo>". */
    static final String MENSAJE = "pm";

    private static final Locale ES = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("d/M");

    private final PrestamoService prestamos;
    private final FormatoMensajes formato;
    private final Clock clock;

    public ConversacionPrestamos(PrestamoService prestamos, FormatoMensajes formato, Clock clock) {
        this.prestamos = prestamos;
        this.formato = formato;
        this.clock = clock;
    }

    static boolean esBoton(String accion) {
        return switch (accion) {
            case GUARDAR, CANCELAR, COBRAR, COBRAR_PROXIMA, MENSAJE -> true;
            default -> false;
        };
    }

    // --- Con un gasto ---

    /**
     * El gasto recién creado (con el total pagado) lleva un préstamo: queda con mi parte. Si el préstamo no cierra
     * (no es menor que el total), el gasto queda como estaba.
     *
     * @return si se creó el préstamo
     */
    public boolean crearConGasto(Movimiento m, PedidoPrestamo pedido) {
        try {
            prestamos.crearConGasto(m.getId(), datos(pedido));
            return true;
        } catch (PrestamoInvalidoException e) {
            return false;
        }
    }

    static PrestamoService.Datos datos(PedidoPrestamo p) {
        return new PrestamoService.Datos(p.persona(), p.monto(), p.cuotas(), p.valorCuota(), p.primerMes(), p.dia());
    }

    public Optional<VistaPrestamo> deMovimiento(long movimientoId) {
        return prestamos.deMovimiento(movimientoId);
    }

    /** Se cambió el monto en la tarjeta del gasto: es el total pagado, y mi parte es el resto. */
    public Movimiento cambiarTotal(long movimientoId, BigDecimal total) {
        return prestamos.cambiarTotal(movimientoId, total);
    }

    /** "🤝 Préstamo a Martín $30.000 · 6 cuotas de $5.000 desde diciembre": la línea de la tarjeta del gasto. */
    String linea(VistaPrestamo p) {
        return "🤝 Préstamo a <b>" + html(p.persona()) + "</b> " + formato.pesos(p.monto()) + " · " + cuotas(p);
    }

    /** "6 cuotas de $5.000 desde diciembre" o "1 cuota en diciembre". */
    String cuotas(VistaPrestamo p) {
        return cuotas(p, formato);
    }

    static String cuotas(VistaPrestamo p, FormatoMensajes formato) {
        String mes = mes(p.primerMes(), YearMonth.from(p.fecha()));
        if (p.cantidadCuotas() == 1) {
            return "1 cuota en " + mes;
        }
        return p.cantidadCuotas() + " cuotas de " + formato.pesos(p.valorCuota())
                + (p.cuotasIguales() ? "" : " (la última distinta)") + " desde " + mes;
    }

    /** El nombre del mes; con el año solo si falta un año o más desde el préstamo. */
    private static String mes(YearMonth mes, YearMonth desde) {
        String nombre = mes.getMonth().getDisplayName(TextStyle.FULL, ES);
        return mes.isBefore(desde.plusMonths(12)) ? nombre : nombre + " " + mes.getYear();
    }

    // --- Solo ---

    /** "Le presté 30 mil a Martín…": la tarjeta para confirmarlo. Sin a quién o cuánto, se pregunta. */
    public Accion.Enviar nuevo(ValidadorInterpretacion.Prestamo p, String texto, Long viajeId, String prefijo) {
        if (p.pedido() == null) {
            return Accion.Enviar.texto(prefijo + "🤝 ¿A quién le prestaste y cuánto? Contámelo así: \"le presté "
                    + "30.000 a Martín, me lo devuelve en 6 cuotas desde diciembre\".");
        }
        VistaPrestamo v;
        try {
            v = prestamos.crearSolo(datos(p.pedido()), p.descripcion(), p.cuenta(), p.medio(),
                    p.fechaHora().toLocalDate(), viajeId, texto);
        } catch (PrestamoInvalidoException e) {
            return Accion.Enviar.texto(prefijo + "🤔 " + html(e.getMessage()) + ". ¿Me lo contás de nuevo?");
        }
        return Accion.Enviar.conBotones(prefijo + tarjeta(v, "🤝 <b>Nuevo préstamo</b>"), Teclado.constructor()
                .fila(new Teclado.Boton("✅ Guardar", GUARDAR + ":" + v.id()),
                        new Teclado.Boton("❌ Cancelar", CANCELAR + ":" + v.id()))
                .build());
    }

    /** El préstamo con sus cuotas. */
    String tarjeta(VistaPrestamo p, String titulo) {
        StringBuilder sb = new StringBuilder(titulo).append("\n💰 <b>").append(formato.pesos(p.monto()))
                .append("</b> a ").append(html(p.persona()));
        if (p.descripcion() != null && !p.descripcion().isBlank()) {
            sb.append(" · ").append(html(p.descripcion()));
        }
        sb.append("\n🗓 ").append(cuotas(p)).append(", los ").append(p.dia());
        if (p.billetera() != null) {
            sb.append("\n💳 Salió de ").append(html(p.billetera()));
        }
        if (p.cuotasCobradas() > 0 || p.terminado()) {
            sb.append("\n📥 ").append(estado(p));
        }
        sb.append("\n\n").append(listaDeCuotas(p));
        sb.append("\n\n<i>No es gasto tuyo: es plata a cobrar.</i>");
        return sb.toString();
    }

    private String listaDeCuotas(VistaPrestamo p) {
        List<String> lineas = new ArrayList<>();
        for (VistaPrestamo.Cuota c : p.cuotas()) {
            lineas.add(c.numero() + ". " + DIA_MES.format(c.vence()) + " · " + formato.pesos(c.monto()) + " "
                    + switch (c.estado()) {
                        case COBRADA -> "✅";
                        case ATRASADA -> "⏰ atrasada";
                        case PENDIENTE -> "⏳";
                    });
        }
        return String.join("\n", lineas);
    }

    /** "Martín: 1 de 6 cuotas, faltan $25.000" o "Martín ya te pagó todo". */
    String estado(VistaPrestamo p) {
        if (p.terminado()) {
            return html(p.persona()) + " ya te pagó todo 🎉";
        }
        return html(p.persona()) + ": " + p.cuotasCobradas() + " de " + p.cantidadCuotas() + " cuotas, faltan <b>"
                + formato.pesos(p.restante()) + "</b>";
    }

    // --- Lista ---

    public List<VistaPrestamo> conDeuda() {
        return prestamos.conDeuda();
    }

    /** La tarjeta de un préstamo con deuda, para "/prestamos" y "¿quién me debe?". */
    Accion.Enviar abierto(VistaPrestamo p) {
        return Accion.Enviar.conBotones(tarjeta(p, "🤝 <b>Préstamo</b>"), botones(p));
    }

    private static Teclado botones(VistaPrestamo p) {
        if (p.terminado()) {
            return Teclado.NINGUNO;
        }
        return Teclado.constructor()
                .fila(new Teclado.Boton("✅ Me pasó la cuota", COBRAR_PROXIMA + ":" + p.id()),
                        new Teclado.Boton("💬 Armar mensaje", MENSAJE + ":" + p.id()))
                .build();
    }

    // --- Devoluciones ---

    /** Los préstamos a los que puede corresponder una devolución. */
    List<VistaPrestamo> deudores(String persona, BigDecimal monto) {
        return prestamos.deudores(persona, monto);
    }

    /** Un botón para elegir este préstamo en una devolución. */
    Teclado.Boton opcion(long devolucionId, VistaPrestamo p) {
        VistaPrestamo.Cuota c = p.proxima();
        return new Teclado.Boton(p.persona() + " · cuota " + c.numero() + " de " + p.cantidadCuotas() + " · "
                + formato.pesos(c.monto()), COBRAR + ":" + devolucionId + ":" + p.id());
    }

    /** "🤝 Martín, cuota 2 de 6 de los vuelos (te debe $25.000)". */
    String deQuien(VistaPrestamo p) {
        VistaPrestamo.Cuota c = p.proxima();
        return "🤝 " + html(p.persona()) + ", cuota " + c.numero() + " de " + p.cantidadCuotas()
                + (p.descripcion() == null ? "" : " de " + html(p.descripcion().toLowerCase(ES)))
                + " (te debe " + formato.pesos(p.restante()) + ")";
    }

    // --- Botones ---

    public List<Accion> boton(int mensajeId, String accion, long id, Long elegido) {
        try {
            return switch (accion) {
                case GUARDAR -> {
                    VistaPrestamo v = prestamos.confirmar(id);
                    yield List.of(new Accion.Editar(mensajeId, tarjeta(v, "✅ <b>Préstamo guardado</b>"),
                            botones(v)), new Accion.Avisar("✅ Guardado"));
                }
                case CANCELAR -> {
                    prestamos.cancelar(id);
                    yield List.of(new Accion.Editar(mensajeId, "❌ <b>Cancelado</b>: no quedó guardado el préstamo.",
                            Teclado.NINGUNO), new Accion.Avisar("Cancelado"));
                }
                case COBRAR -> {
                    if (elegido == null) {
                        throw new PrestamoInvalidoException("Botón inválido");
                    }
                    yield cobrado(mensajeId, prestamos.cobrar(id, elegido));
                }
                case COBRAR_PROXIMA -> cobrado(mensajeId, prestamos.cobrarProxima(id, Origen.TEXTO));
                case MENSAJE -> List.of(Accion.Enviar.texto(mensajeParaLaPersona(prestamos.vista(id))),
                        new Accion.Avisar("💬 Listo para copiar"));
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (PrestamoInvalidoException e) {
            return List.of(new Accion.Avisar(e.getMessage()));
        }
    }

    /** "✅ Cobrado · Martín: 1 de 6 cuotas, faltan $25.000", y si pagó distinto, cómo quedaron las que siguen. */
    private List<Accion> cobrado(int mensajeId, PrestamoService.Cobro c) {
        VistaPrestamo p = c.prestamo();
        BigDecimal pago = c.devolucion().getMonto();
        StringBuilder sb = new StringBuilder("✅ <b>Cobrado</b> · " + formato.pesos(pago) + "\n"
                + html(c.devolucion().getDescripcion()) + "\n📥 " + estado(p));
        if (c.excedente().signum() > 0) {
            sb.append("\nTe pasó ").append(formato.pesos(c.excedente())).append(" de más.");
        } else if (c.acomodado()) {
            VistaPrestamo.Cuota proxima = p.proxima();
            sb.append("\nTe pasó ").append(formato.pesos(pago)).append(" y eran ").append(formato.pesos(c.debia()))
                    .append(": las cuotas que siguen se acomodaron")
                    .append(proxima == null ? "." : " (la próxima, " + formato.pesos(proxima.monto()) + " el "
                            + DIA_MES.format(proxima.vence()) + ").");
        }
        sb.append("\n\n<i>Es una devolución: no cuenta como ingreso.</i>");
        return List.of(new Accion.Editar(mensajeId, sb.toString(), botones(p)), new Accion.Avisar("✅ Cobrado"));
    }

    /** Un texto para copiar y mandarle a la persona. El bot no le escribe. */
    String mensajeParaLaPersona(VistaPrestamo p) {
        VistaPrestamo.Cuota c = p.proxima();
        String de = p.descripcion() == null ? "lo que te presté" : "lo de " + p.descripcion().toLowerCase(ES);
        StringBuilder sb = new StringBuilder("Hola! Te escribo por " + de + ". ");
        if (c == null) {
            sb.append("Ya está todo pagado, ¡gracias!");
        } else {
            boolean vencida = !c.vence().isAfter(LocalDate.now(clock));
            sb.append(vencida ? "Me quedó pendiente la cuota " : "Te recuerdo la cuota ").append(c.numero())
                    .append(" de ").append(p.cantidadCuotas()).append(": ").append(formato.pesos(c.monto()))
                    .append(vencida ? " (era para el " : " (para el ").append(DIA_MES.format(c.vence())).append(").");
            if (p.restante().compareTo(c.monto()) > 0) {
                sb.append(" En total faltan ").append(formato.pesos(p.restante())).append(".");
            }
            sb.append(p.billetera() == null ? " ¡Gracias!" : " Me la podés pasar a " + p.billetera() + ". ¡Gracias!");
        }
        return "💬 Copiá esto y mandáselo a " + html(p.persona()) + ":\n\n<pre>" + html(sb.toString()) + "</pre>";
    }

    // --- Atrasadas ---

    /** El aviso de una cuota atrasada, con Armar mensaje y Ya me la pasó. */
    Accion.Enviar aviso(PrestamoService.Atrasada a) {
        VistaPrestamo p = a.prestamo();
        VistaPrestamo.Cuota c = a.cuota();
        String html = "⏰ <b>" + html(p.persona()) + " no te pasó la cuota " + c.numero() + " de "
                + p.cantidadCuotas() + "</b>\n" + formato.pesos(c.monto()) + ", que era para el "
                + DIA_MES.format(c.vence()) + (p.descripcion() == null ? "" : " · " + html(p.descripcion()))
                + "\n📥 En total te debe " + formato.pesos(p.restante()) + ".";
        return Accion.Enviar.conBotones(html, botones(p));
    }
}
