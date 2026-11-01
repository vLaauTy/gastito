package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ParserTarjeta;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Origen;
import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.VistaResumenTarjeta;

/**
 * La tarjeta de crédito en el bot: cuánto llevo y cuándo vence (💳 Tarjeta, /tarjeta o preguntado), las fechas del
 * próximo resumen, el total que dice el banco, el débito y los avisos (fechas que faltan, vencimiento cerca, "¿se
 * debitó?"). Las fechas estimadas van con ≈.
 */
@Component
public class ConversacionTarjeta {

    /** "Todavía no" sé las fechas: "tf:<resumen>". */
    static final String TODAVIA_NO_FECHAS = "tf";
    /** "Cargar total del resumen": "tt:<resumen>"; el total se escribe después. */
    static final String CARGAR_TOTAL = "tt";
    /** "Sí" se debitó (pregunta del día del vencimiento): "ts:<resumen>". */
    static final String DEBITADO = "ts";
    /** "Todavía no" se debitó: "tn:<resumen>" (se vuelve a preguntar mañana). */
    static final String TODAVIA_NO_DEBITO = "tn";
    /** Confirmar el débito dicho en un mensaje: "tc:<resumen>:<centavos>". */
    static final String CONFIRMAR_PAGO = "tc";
    static final String CANCELAR_PAGO = "tx";

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");

    /** Lo que se espera que el usuario escriba: el total de un resumen, o la fecha que faltó. */
    private sealed interface Espera {
    }

    private record EsperaTotal(long resumenId) implements Espera {
    }

    /** Dijo una sola de las dos fechas: la otra. */
    private record EsperaFecha(LocalDate cierre, LocalDate vencimiento) implements Espera {
    }

    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();

    private final CicloTarjetaService ciclo;
    private final FormatoMensajes formato;

    public ConversacionTarjeta(CicloTarjetaService ciclo, FormatoMensajes formato) {
        this.ciclo = ciclo;
        this.formato = formato;
    }

    // --- Cómo viene ---

    /** /tarjeta, 💳 Tarjeta, "¿cuánto llevo de tarjeta?", "¿cuánto vence el 11?". */
    List<Accion> estado() {
        Optional<CicloTarjetaService.Panel> panel = ciclo.panel();
        if (panel.isEmpty()) {
            return List.of(Accion.Enviar.conMenu("""
                    💳 Todavía no sé cuándo cierra la tarjeta.
                    Contame el último cierre y su vencimiento, como te salga (por ejemplo: <i>la tarjeta cerró el 29/10 y vence el 11/11</i>) y desde ahí sigo yo."""));
        }
        CicloTarjetaService.Panel p = panel.get();
        List<String> partes = new ArrayList<>();
        partes.add("💳 <b>" + html(p.tarjeta()) + "</b>");
        for (VistaResumenTarjeta r : p.porPagar()) {
            partes.add("🧾 <b>Resumen cerrado por pagar</b>\nVence el " + dia(r.vencimiento(), r.estimadas())
                    + " · según lo cargado <b>" + formato.pesos(r.cargado()) + "</b>"
                    + (r.totalReal() == null ? "" : "\nEl resumen dice <b>" + formato.pesos(r.totalReal()) + "</b>")
                    + (p.cuentaDebito() == null ? "" : "\nSe debita de " + html(p.cuentaDebito()) + "."));
        }
        VistaResumenTarjeta proximo = p.proximo();
        partes.add("🛒 <b>Próximo resumen</b>\nCierra " + dia(proximo.cierre(), proximo.estimadas()) + " · vence "
                + dia(proximo.vencimiento(), proximo.estimadas()) + "\nLlevás <b>" + formato.pesos(proximo.cargado())
                + "</b> (compras " + formato.pesos(proximo.compras()) + " + cuotas " + formato.pesos(proximo.cuotas())
                + ")");
        if (!p.siguientes().isEmpty()) {
            StringBuilder cuotas = new StringBuilder("🗂 <b>Cuotas ya comprometidas</b>");
            for (VistaResumenTarjeta r : p.siguientes()) {
                cuotas.append("\n• Vence ").append(dia(r.vencimiento(), r.estimadas())).append(": ")
                        .append(formato.pesos(r.cuotas()));
            }
            partes.add(cuotas.toString());
        }
        if (proximo.estimadas()) {
            partes.add("<i>≈ es una estimación. Cuando sepas las fechas, contámelas (<b>la tarjeta cierra el … y "
                    + "vence el …</b>).</i>");
        }
        return List.of(Accion.Enviar.conMenu(String.join("\n\n", partes)));
    }

    // --- Lo que dice el usuario ---

    /** "La tarjeta cierra el 26/11 y vence el 11/12". Si falta una de las dos, la pregunta. */
    List<Accion> fechas(long chatId, ValidadorInterpretacion.FechasTarjeta f, String prefijo) {
        if (f.cierre() == null || f.vencimiento() == null) {
            esperas.put(chatId, new EsperaFecha(f.cierre(), f.vencimiento()));
            return List.of(Accion.Enviar.texto(prefijo + (f.cierre() == null
                    ? "💳 Vence el " + dia(f.vencimiento(), false) + ". ¿Y cuándo cierra?"
                    : "💳 Cierra el " + dia(f.cierre(), false) + ". ¿Y cuándo vence?")));
        }
        try {
            CicloTarjetaService.FechasCargadas cargadas = ciclo.cargarFechas(f.cierre(), f.vencimiento());
            VistaResumenTarjeta r = cargadas.resumen();
            String antes = cargadas.antes() == null || cargadas.antes().confirmadas()
                    || (cargadas.antes().cierre().equals(r.cierre())
                            && cargadas.antes().vencimiento().equals(r.vencimiento())) ? ""
                    : "\n<i>Lo tenía estimado para el " + dia(cargadas.antes().cierre(), true) + " y el "
                            + dia(cargadas.antes().vencimiento(), true) + ": las compras ya quedaron en su resumen.</i>";
            return List.of(Accion.Enviar.conMenu(prefijo + "💳 Listo: el resumen cierra el "
                    + dia(r.cierre(), false) + " y vence el " + dia(r.vencimiento(), false) + "." + antes
                    + "\nEn ese resumen llevás <b>" + formato.pesos(r.cargado()) + "</b>."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 No pude guardar esas fechas: " + html(e.getMessage())
                    + "."));
        }
    }

    /** "El resumen de la tarjeta es de 250.000": se compara con lo cargado; no crea gastos. */
    List<Accion> total(BigDecimal total, Long resumenId, String prefijo) {
        try {
            CicloTarjetaService.TotalCargado t = ciclo.cargarTotal(resumenId, total);
            return List.of(Accion.Enviar.conMenu(prefijo + textoTotal(t)));
        } catch (NoSuchElementException e) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 " + html(e.getMessage()) + "."));
        }
    }

    String textoTotal(CicloTarjetaService.TotalCargado t) {
        String encabezado = "🧾 Resumen que vence el " + dia(t.resumen().vencimiento(), t.resumen().estimadas())
                + ": <b>" + formato.pesos(t.total()) + "</b>.\n";
        if (t.coincide()) {
            return encabezado + "✅ Coincide con lo que cargaste.";
        }
        return encabezado + "⚠️ El resumen dice " + formato.pesos(t.total()) + " y cargaste "
                + formato.pesos(t.cargado()) + ": " + (t.diferencia().signum() > 0
                        ? "faltan <b>" + formato.pesos(t.diferencia()) + "</b> sin cargar."
                        : "cargaste <b>" + formato.pesos(t.diferencia().negate()) + "</b> de más.")
                + "\nNo agregué nada: revisalo cuando puedas.";
    }

    /** "Se debitó la tarjeta, 250.000": se confirma con un botón antes de registrar el pago. */
    List<Accion> pago(BigDecimal monto, String prefijo) {
        Optional<VistaResumenTarjeta> resumen = ciclo.panel().flatMap(p -> p.porPagar().stream().findFirst());
        if (resumen.isEmpty()) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 No tengo ningún resumen cerrado sin debitar."));
        }
        VistaResumenTarjeta r = resumen.get();
        BigDecimal debitado = monto != null ? monto : r.aDebitar();
        if (debitado.signum() <= 0) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 ¿De cuánto fue el débito?"));
        }
        long centavos = debitado.movePointRight(2).longValue();
        return List.of(Accion.Enviar.conBotones(prefijo + "💳 ¿Registro el débito del resumen que vence el "
                + dia(r.vencimiento(), r.estimadas()) + "?\n<b>" + formato.pesos(debitado) + "</b>"
                + (r.cuentaDebito() == null ? "" : " desde " + html(r.cuentaDebito()))
                + ". No cuenta como gasto: las compras ya se contaron.",
                Teclado.constructor().fila(new Teclado.Boton("✅ Sí, registrar", CONFIRMAR_PAGO + ":" + r.id() + ":"
                        + centavos), new Teclado.Boton("❌ Cancelar", CANCELAR_PAGO + ":" + r.id())).build()));
    }

    // --- Avisos (los manda TarjetaProgramada) ---

    Accion.Enviar preguntaDeFechas(VistaResumenTarjeta proximo) {
        return Accion.Enviar.conBotones("💳 ¿Ya sabés el próximo cierre y vencimiento de la tarjeta?\n"
                + "Por ahora los estimo: cierra " + dia(proximo.cierre(), true) + " · vence "
                + dia(proximo.vencimiento(), true) + ".\nCuando los sepas, contámelos como te salga.",
                Teclado.constructor().fila(new Teclado.Boton("⏳ Todavía no", TODAVIA_NO_FECHAS + ":" + proximo.id()))
                        .build());
    }

    Accion.Enviar avisoDeVencimiento(VistaResumenTarjeta r) {
        return Accion.Enviar.conBotones("💳 El " + dia(r.vencimiento(), r.estimadas()) + " vence la tarjeta: "
                + (r.totalReal() == null ? "según lo cargado, <b>" + formato.pesos(r.cargado()) + "</b>."
                        : "<b>" + formato.pesos(r.totalReal()) + "</b> (lo cargado: " + formato.pesos(r.cargado())
                                + ").")
                + (r.cuentaDebito() == null ? "" : " Se debita de " + html(r.cuentaDebito()) + "."),
                Teclado.constructor().fila(new Teclado.Boton("🧾 Cargar total del resumen", CARGAR_TOTAL + ":"
                        + r.id())).build());
    }

    Accion.Enviar preguntaDeDebito(VistaResumenTarjeta r) {
        return Accion.Enviar.conBotones("💳 ¿Se debitó el resumen de la tarjeta (<b>" + formato.pesos(r.aDebitar())
                + "</b>)?", Teclado.constructor().fila(new Teclado.Boton("✅ Sí", DEBITADO + ":" + r.id()),
                        new Teclado.Boton("⏳ Todavía no", TODAVIA_NO_DEBITO + ":" + r.id())).build());
    }

    // --- Botones ---

    List<Accion> boton(long chatId, int mensajeId, String accion, long resumenId, Long elegido) {
        try {
            return switch (accion) {
                case TODAVIA_NO_FECHAS -> {
                    LocalDate hasta = ciclo.posponerFechas(resumenId);
                    yield List.of(new Accion.Editar(mensajeId, "👌 Te vuelvo a preguntar el " + dia(hasta, false)
                            + ". Si las sabés antes, contámelas.", Teclado.NINGUNO));
                }
                case CARGAR_TOTAL -> {
                    esperas.put(chatId, new EsperaTotal(resumenId));
                    yield List.of(new Accion.QuitarBotones(mensajeId),
                            Accion.Enviar.texto("🧾 ¿De cuánto es el resumen? Escribime el total."));
                }
                case DEBITADO -> pagar(mensajeId, resumenId, null, Origen.AUTOMATICO);
                case CONFIRMAR_PAGO -> pagar(mensajeId, resumenId,
                        elegido == null ? null : BigDecimal.valueOf(elegido, 2), Origen.TEXTO);
                case TODAVIA_NO_DEBITO -> List.of(new Accion.Editar(mensajeId,
                        "👌 Mañana te pregunto de nuevo si se debitó.", Teclado.NINGUNO));
                case CANCELAR_PAGO -> List.of(new Accion.Editar(mensajeId, "❌ No registré el débito.",
                        Teclado.NINGUNO), new Accion.Avisar("Cancelado"));
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (IllegalStateException | IllegalArgumentException | NoSuchElementException e) {
            return List.of(new Accion.Editar(mensajeId, "🤔 " + html(e.getMessage()) + ".", Teclado.NINGUNO));
        }
    }

    private List<Accion> pagar(int mensajeId, long resumenId, BigDecimal monto, Origen origen) {
        CicloTarjetaService.Pago pago = ciclo.pagar(resumenId, monto, origen, null);
        VistaResumenTarjeta r = pago.resumen();
        return List.of(new Accion.Editar(mensajeId, "✅ <b>Débito registrado</b>: " + formato.pesos(
                pago.movimiento().getMonto()) + (r.cuentaDebito() == null ? "" : " desde " + html(r.cuentaDebito()))
                + ".\nEl resumen que vencía el " + dia(r.vencimiento(), false)
                + " quedó pagado. No cuenta como gasto.", Teclado.NINGUNO), new Accion.Avisar("✅ Registrado"));
    }

    // --- Lo que se espera que escriba ---

    boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    void olvidar(long chatId) {
        esperas.remove(chatId);
    }

    /** El total o la fecha que se pidió. Si el texto no sirve, se olvida y el mensaje se procesa normal. */
    Optional<List<Accion>> texto(long chatId, String texto) {
        Espera espera = esperas.remove(chatId);
        return switch (espera) {
            case EsperaTotal t -> Montos.parsearConPalabras(texto.replace("$", "").strip())
                    .map(total -> total(total, t.resumenId(), ""));
            case EsperaFecha f -> f.cierre() == null
                    ? ParserTarjeta.cierre(texto, f.vencimiento().minusDays(CICLO_MAXIMO_DIAS))
                            .filter(c -> c.isBefore(f.vencimiento()))
                            .map(c -> fechas(chatId, new ValidadorInterpretacion.FechasTarjeta(c, f.vencimiento()), ""))
                    : ParserTarjeta.vencimiento(texto, f.cierre())
                            .map(v -> fechas(chatId, new ValidadorInterpretacion.FechasTarjeta(f.cierre(), v), ""));
            case null -> Optional.empty();
        };
    }

    /** Para buscar un cierre que se dijo después del vencimiento: hasta cuántos días antes puede estar. */
    private static final int CICLO_MAXIMO_DIAS = 40;

    /** "11/11", o "≈ 14/12" si es una estimación. */
    private static String dia(LocalDate dia, boolean estimada) {
        return (estimada ? "≈ " : "") + DIA_MES.format(dia);
    }
}
