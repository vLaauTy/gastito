package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.PagoFijoService;
import ar.gastito.fijo.RecordatorioFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.interpretacion.Montos;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * Los gastos fijos en el chat: "/fijos" (o "¿qué fijos me faltan?") con el checklist y la baja, la pregunta de cada
 * cobro y el recordatorio de los vencidos. El alta está en {@link ConversacionAltaFijo}.
 */
@Component
public class ConversacionFijos {

    /** Botón de la baja desde la lista. */
    static final String BAJA = "fb";
    /** "Este mes no" desde el checklist de /fijos (todavía no se generó el cobro automático). */
    static final String ESTE_MES_NO_CHECKLIST = "fe";

    private final GastoFijoService fijos;
    private final MovimientoService movimientos;
    private final FormatoMensajes formato;
    private final PagoFijoService pagos;
    private final RecordatorioFijoService recordatorios;
    private final Clock clock;

    public ConversacionFijos(GastoFijoService fijos, MovimientoService movimientos, FormatoMensajes formato,
            PagoFijoService pagos, RecordatorioFijoService recordatorios, Clock clock) {
        this.fijos = fijos;
        this.movimientos = movimientos;
        this.formato = formato;
        this.pagos = pagos;
        this.recordatorios = recordatorios;
        this.clock = clock;
    }

    // --- Botones de la lista ---

    public boolean esBoton(String accion) {
        return BAJA.equals(accion) || ESTE_MES_NO_CHECKLIST.equals(accion);
    }

    public List<Accion> boton(int mensajeId, String accion, long id) {
        if (BAJA.equals(accion)) {
            VistaFijo f = fijos.vista(id);
            fijos.darDeBaja(id);
            return List.of(new Accion.Avisar("🗑 " + f.nombre() + " ya no se cobra"));
        }
        YearMonth mes = YearMonth.now(clock);
        fijos.esteMesNoDelPeriodo(id, mes);
        List<GastoFijoService.ItemChecklist> items = fijos.checklist(mes);
        return List.of(new Accion.Editar(mensajeId, textoDeChecklist(mes, items), tecladoDeChecklist(items)),
                new Accion.Avisar("⏭ Listo"));
    }

    /**
     * Con cuánto se tildaría "Pagado" ese fijo este mes (el monto de referencia), si todavía falta y lo tiene. Para
     * "ya pagué el seguro", dicho sin monto.
     */
    public Optional<BigDecimal> aproximado(Long fijoId) {
        return fijos.checklist(YearMonth.now(clock)).stream()
                .filter(i -> i.fijoId().equals(fijoId) && i.estado() == GastoFijoService.EstadoChecklist.FALTA)
                .map(GastoFijoService.ItemChecklist::aproximado).filter(java.util.Objects::nonNull).findFirst();
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
            return List.of(new Accion.Avisar("No entendí el monto 🤔 ¿Cuánto se cobró?"));
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
            return "📌 No tenés gastos fijos. Para agregar uno, contámelo: <i>agregá un fijo, el seguro de la moto, "
                    + "32 mil por mes con la tarjeta</i>.";
        }
        StringBuilder sb = new StringBuilder("📋 <b>Fijos de " + formato.mes(mes) + "</b>");
        BigDecimal cargado = BigDecimal.ZERO;
        int faltan = 0;
        for (GastoFijoService.ItemChecklist item : items) {
            sb.append('\n');
            switch (item.estado()) {
                case CARGADO -> {
                    sb.append("✅ ").append(html(item.nombre())).append(": ").append(item.estimado() ? "≈ " : "")
                            .append(formato.pesos(item.monto())).append(item.estimado() ? " · estimado" : "");
                    if (item.porcentaje() != null && !item.estimado()) {
                        sb.append(" (").append(item.porcentaje().signum() > 0 ? "+" : "")
                                .append(item.porcentaje().stripTrailingZeros().toPlainString().replace('.', ','))
                                .append("%)");
                    }
                    cargado = cargado.add(item.monto());
                }
                case ESTE_MES_NO -> sb.append("➖ ").append(html(item.nombre())).append(": este mes no");
                case FALTA -> {
                    sb.append("⏳ ").append(html(item.nombre())).append(item.vencimiento() == null ? ": sin día"
                            : ": vence el " + item.vencimiento().getDayOfMonth());
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

    /** Botón del recordatorio: "✅ Luz ≈ $85.000" lo tilda como pagado con el monto estimado. */
    static final String PAGADO = "fp";

    /**
     * "Te falta cargar: luz (vence el 10), gas (vence el 12)", con un botón por fijo para tildarlo pagado con el
     * monto aproximado (igual que en la web).
     */
    public Accion.Enviar recordatorio(List<RecordatorioFijoService.Vencido> vencidos) {
        return Accion.Enviar.conBotones(textoDeRecordatorio(vencidos), tecladoDeRecordatorio(vencidos));
    }

    private static String textoDeRecordatorio(List<RecordatorioFijoService.Vencido> vencidos) {
        String lista = vencidos.stream()
                .map(v -> html(v.nombre()) + " (vence el " + v.diaVencimiento() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "📌 <b>Te falta cargar:</b> " + lista + ". Si ya lo pagaste, tocalo y queda con el monto aproximado; "
                + "el real me lo decís cuando lo sepas.";
    }

    private Teclado tecladoDeRecordatorio(List<RecordatorioFijoService.Vencido> vencidos) {
        Teclado.Constructor t = Teclado.constructor();
        vencidos.stream().filter(v -> v.aproximado() != null).forEach(v -> t.fila(new Teclado.Boton(
                "✅ " + v.nombre() + " ≈ " + formato.pesos(v.aproximado()), PAGADO + ":" + v.fijoId())));
        return t.build();
    }

    /** "✅ Luz ≈ $85.000" del recordatorio, o "Actualizar el monto" de un estimado. */
    public List<Accion> botonDePago(int mensajeId, String accion, long id) {
        if (ACTUALIZAR_ESTIMADO.equals(accion)) {
            return actualizarEstimado(mensajeId, id);
        }
        Movimiento m;
        try {
            m = pagos.pagado(id, YearMonth.now(clock), Origen.TEXTO);
        } catch (IllegalStateException e) {
            m = null;
        }
        List<RecordatorioFijoService.Vencido> faltan = recordatorios.vencidos();
        Accion.Editar editado = faltan.isEmpty()
                ? new Accion.Editar(mensajeId, "✅ <b>Listo:</b> no te falta cargar ningún fijo vencido. Los que "
                        + "quedaron con monto aproximado los corregís cuando sepas el real.", Teclado.NINGUNO)
                : new Accion.Editar(mensajeId, textoDeRecordatorio(faltan), tecladoDeRecordatorio(faltan));
        return List.of(editado, new Accion.Avisar(m == null ? "Ya estaba cargado"
                : "✅ " + m.getDescripcion() + " ≈ " + formato.pesos(m.getMonto())));
    }

    // --- Monto estimado ---

    /** Botón de la propuesta: el gasto que se acaba de mandar es el real del que se tildó con el estimado. */
    static final String ACTUALIZAR_ESTIMADO = "fu";
    /** "Es otro pago": sigue como un gasto nuevo, con su tarjeta de siempre. */
    static final String OTRO_PAGO = "fo";

    /**
     * "De luz pagué 87.500" y la luz de este mes estaba tildada con el estimado: se propone actualizar ese monto
     * (sin crear otro gasto) o, si no, seguir con un gasto aparte.
     */
    public Accion.Enviar propuestaDeActualizar(Movimiento nuevo, Movimiento estimado) {
        String nombre = html(estimado.getDescripcion());
        return Accion.Enviar.conBotones("📌 <b>" + nombre + "</b> de " + formato.mes(YearMonth.from(
                estimado.getFijoPeriodo())) + " está cargada con monto estimado (≈ " + formato.pesos(
                        estimado.getMonto()) + ").\n¿Es este el monto real?",
                Teclado.constructor()
                        .fila(new Teclado.Boton("🔄 Actualizar el monto (" + formato.pesos(estimado.getMonto()) + " → "
                                + formato.pesos(nuevo.getMonto()) + ")", ACTUALIZAR_ESTIMADO + ":" + nuevo.getId()))
                        .fila(new Teclado.Boton("➕ Es otro pago", OTRO_PAGO + ":" + nuevo.getId()))
                        .build());
    }

    /** La propuesta de actualizar, si el gasto nuevo es de un fijo que este mes quedó con el monto estimado. */
    public Optional<Accion.Enviar> siActualizaUnEstimado(Movimiento nuevo) {
        if (nuevo.getGastoFijoId() == null || !nuevo.isPendiente() || nuevo.getMonto() == null) {
            return Optional.empty();
        }
        return pagos.estimadoDelPeriodo(nuevo.getGastoFijoId(), YearMonth.from(nuevo.getFijoPeriodo()))
                .map(estimado -> propuestaDeActualizar(nuevo, estimado));
    }

    private List<Accion> actualizarEstimado(int mensajeId, long pendienteId) {
        try {
            PagoFijoService.Actualizacion a = pagos.actualizarEstimadoCon(pendienteId);
            Movimiento m = a.movimiento();
            return List.of(new Accion.Editar(mensajeId, "✅ <b>Monto actualizado</b>\n📌 " + html(m.getDescripcion())
                    + ": " + formato.pesos(a.anterior()) + " → " + formato.pesos(m.getMonto()), Teclado.NINGUNO),
                    new Accion.Avisar("✅ Actualizado"));
        } catch (IllegalStateException e) {
            return List.of(new Accion.Editar(mensajeId, "📌 " + html(e.getMessage()) + ".", Teclado.NINGUNO));
        }
    }

    /** "⚠️ Ya cargaste luz este mes ($88.000). ¿Es otro pago?" si el fijo ya tenía su cobro real, o "". */
    public String avisoYaCargado(Movimiento nuevo) {
        if (nuevo.getGastoFijoId() == null) {
            return "";
        }
        return fijos.yaCargadoEstePeriodo(nuevo.getGastoFijoId(), YearMonth.from(nuevo.getFijoPeriodo()))
                .map(a -> "\n\n⚠️ Ya cargaste " + html(minusculaSinArticulo(nuevo.getDescripcion())) + " este mes ("
                        + formato.pesos(a.getMonto()) + "). ¿Es otro pago?")
                .orElse("");
    }

    private static String minusculaSinArticulo(String texto) {
        return texto == null || texto.isEmpty() ? "ese gasto"
                : Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
    }
}
