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
import java.util.function.Function;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.ReconocedorTarjeta;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ParserTarjeta;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.VistaResumenTarjeta;

/**
 * Las tarjetas de crédito en el bot, cada una con su ciclo: cuánto llevo y cuándo vence (💳 Tarjeta, /tarjeta o
 * preguntado: un bloque por tarjeta, o solo la que se nombra), las fechas del próximo resumen, el total que dice el
 * banco, el débito y los avisos (fechas que faltan, vencimiento cerca, "¿se debitó?"). Si no se dice de qué tarjeta
 * y hay más de una posible, se pregunta con un botón por tarjeta. Las fechas estimadas van con ≈.
 */
@Component
public class ConversacionTarjeta {

    /** "Todavía no" sé las fechas: "tf:<resumen>", o "tf:-<tarjeta>" si la tarjeta todavía no tiene ninguno. */
    static final String TODAVIA_NO_FECHAS = "tf";
    /** "Cargar total del resumen": "tt:<resumen>"; el total se escribe después. */
    static final String CARGAR_TOTAL = "tt";
    /** "Sí" se debitó (pregunta del día del vencimiento): "ts:<resumen>". */
    static final String DEBITADO = "ts";
    /** "Todavía no" se debitó: "tn:<resumen>" (se vuelve a preguntar mañana). */
    static final String TODAVIA_NO_DEBITO = "tn";
    /**
     * Confirmar el débito dicho en un mensaje: "tc:<resumen>:<centavos>", o "tc:-<tarjeta>:<centavos>" para un pago
     * de una tarjeta sin resumen cerrado.
     */
    static final String CONFIRMAR_PAGO = "tc";
    static final String CANCELAR_PAGO = "tx";
    /** De qué tarjeta es lo que dijo (fechas, total, débito): "tq:<tarjeta>". */
    static final String ELEGIR_TARJETA = "tq";

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");

    /** Lo que se espera que el usuario escriba: el total de un resumen, o la fecha que faltó. */
    private sealed interface Espera {
    }

    private record EsperaTotal(long resumenId) implements Espera {
    }

    /** Dijo una sola de las dos fechas: la otra. */
    private record EsperaFecha(long tarjetaId, LocalDate cierre, LocalDate vencimiento) implements Espera {
    }

    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();
    /** Lo que dijo sin aclarar de qué tarjeta: se hace cuando la elige con el botón. */
    private final Map<Long, Function<Long, List<Accion>>> sinTarjeta = new ConcurrentHashMap<>();

    private final CicloTarjetaService ciclo;
    private final CatalogoService catalogos;
    private final FormatoMensajes formato;

    public ConversacionTarjeta(CicloTarjetaService ciclo, CatalogoService catalogos, FormatoMensajes formato) {
        this.ciclo = ciclo;
        this.catalogos = catalogos;
        this.formato = formato;
    }

    // --- Cómo viene ---

    /** /tarjeta y 💳 Tarjeta: todas las tarjetas. */
    List<Accion> estado() {
        return estado(null);
    }

    /** "¿Cuánto llevo de tarjeta?" (todas), "¿cuánto llevo de la master?" (solo esa). */
    List<Accion> estado(String texto) {
        CicloTarjetaService.Paneles todos = ciclo.paneles();
        if (todos.tarjetas().isEmpty()) {
            return List.of(Accion.Enviar.conMenu("💳 No tenés ninguna tarjeta de crédito activa."));
        }
        Optional<Long> nombrada = texto == null ? Optional.empty()
                : ReconocedorTarjeta.nombrada(texto, catalogos.cargar()).map(TarjetaCredito::getId);
        List<CicloTarjetaService.Panel> paneles = todos.tarjetas().stream()
                .filter(p -> nombrada.isEmpty() || nombrada.get().equals(p.tarjetaId())).toList();
        if (paneles.isEmpty()) {
            paneles = todos.tarjetas();
        }
        List<String> bloques = new ArrayList<>();
        for (CicloTarjetaService.Panel p : paneles) {
            bloques.add(bloque(p, paneles.size() == 1 && todos.tarjetas().size() == 1));
        }
        if (paneles.size() > 1 && paneles.stream().filter(CicloTarjetaService.Panel::configurado).count() > 1) {
            bloques.add("➕ <b>Entre todas</b>: llevás " + formato.pesos(todos.proximos()) + " en los próximos resúmenes"
                    + (todos.porPagar().signum() == 0 ? "" : " y hay " + formato.pesos(todos.porPagar())
                            + " por pagar") + ".");
        }
        return List.of(Accion.Enviar.conMenu(String.join("\n\n", bloques)));
    }

    /** El bloque de una tarjeta. @param unica es la única tarjeta: se habla de "la tarjeta" */
    private String bloque(CicloTarjetaService.Panel p, boolean unica) {
        if (!p.configurado()) {
            return unica ? """
                    💳 Todavía no sé cuándo cierra la tarjeta.
                    Contame el último cierre y su vencimiento, como te salga (por ejemplo: <i>la tarjeta cerró el 29/10 y vence el 11/11</i>) y desde ahí sigo yo."""
                    : "💳 <b>" + html(p.tarjeta()) + "</b>\nTodavía no sé cuándo cierra. Contame el último cierre y "
                            + "su vencimiento (por ejemplo: <i>la " + html(p.tarjeta()) + " cerró el 29/10 y vence el "
                            + "11/11</i>) y desde ahí sigo yo.";
        }
        List<String> partes = new ArrayList<>();
        partes.add("💳 <b>" + html(p.tarjeta()) + "</b>");
        for (VistaResumenTarjeta r : p.porPagar()) {
            partes.add("🧾 <b>Resumen cerrado por pagar</b>\nVence el " + dia(r.vencimiento(), r.estimadas())
                    + " · según lo cargado <b>" + formato.pesos(r.cargado()) + "</b>"
                    + comprasEnDolares(r)
                    + (r.totalReal() == null ? "" : "\nEl resumen dice <b>" + formato.pesos(r.totalReal()) + "</b>")
                    + (p.cuentaDebito() == null ? "" : "\nSe debita de " + html(p.cuentaDebito()) + "."));
        }
        VistaResumenTarjeta proximo = p.proximo();
        partes.add("🛒 <b>Próximo resumen</b>\nCierra " + dia(proximo.cierre(), proximo.estimadas()) + " · vence "
                + dia(proximo.vencimiento(), proximo.estimadas()) + "\nLlevás <b>" + formato.pesos(proximo.cargado())
                + "</b> (compras " + formato.pesos(proximo.compras()) + " + cuotas " + formato.pesos(proximo.cuotas())
                + ")" + comprasEnDolares(proximo));
        if (!p.siguientes().isEmpty()) {
            StringBuilder cuotas = new StringBuilder("🗂 <b>Cuotas ya comprometidas</b>");
            for (VistaResumenTarjeta r : p.siguientes()) {
                cuotas.append("\n• Vence ").append(dia(r.vencimiento(), r.estimadas())).append(": ")
                        .append(formato.pesos(r.cuotas()));
            }
            partes.add(cuotas.toString());
        }
        if (proximo.estimadas()) {
            partes.add("<i>≈ es una estimación. Cuando sepas las fechas, contámelas (<b>la " + (unica ? "tarjeta"
                    : html(p.tarjeta())) + " cierra el … y vence el …</b>).</i>");
        }
        return String.join("\n\n", partes);
    }

    // --- De qué tarjeta ---

    /** Para qué hace falta saber la tarjeta: cambia entre cuáles se elige si no se nombró ninguna. */
    private enum Para {
        /** Fechas: cualquier tarjeta activa. */
        FECHAS,
        /** Total o débito: las que tienen un resumen cerrado sin pagar (si ninguna, todas). */
        RESUMEN_CERRADO
    }

    /**
     * Hace {@code accion} con la tarjeta que nombra el texto; si no nombra ninguna y hay una sola posible, con esa;
     * si hay varias, pregunta con un botón por tarjeta y la hace al elegir.
     */
    private List<Accion> conTarjeta(long chatId, String texto, Para para, String pregunta, String prefijo,
            Function<Long, List<Accion>> accion) {
        Catalogo catalogo = catalogos.cargar();
        Optional<TarjetaCredito> nombrada = texto == null ? Optional.empty()
                : ReconocedorTarjeta.nombrada(texto, catalogo);
        if (nombrada.isPresent()) {
            return accion.apply(nombrada.get().getId());
        }
        List<TarjetaCredito> posibles = catalogo.tarjetas();
        if (para == Para.RESUMEN_CERRADO && posibles.size() > 1) {
            List<Long> conCerrado = ciclo.paneles().tarjetas().stream().filter(p -> !p.porPagar().isEmpty())
                    .map(CicloTarjetaService.Panel::tarjetaId).toList();
            if (!conCerrado.isEmpty()) {
                posibles = posibles.stream().filter(t -> conCerrado.contains(t.getId())).toList();
            }
        }
        if (posibles.isEmpty()) {
            return List.of(Accion.Enviar.texto(prefijo + "💳 No tenés ninguna tarjeta de crédito activa."));
        }
        if (posibles.size() == 1) {
            return accion.apply(posibles.getFirst().getId());
        }
        sinTarjeta.put(chatId, accion);
        Teclado.Constructor t = Teclado.constructor();
        for (TarjetaCredito tarjeta : posibles) {
            t.fila(new Teclado.Boton("💳 " + tarjeta.getNombre(), ELEGIR_TARJETA + ":" + tarjeta.getId()));
        }
        return List.of(Accion.Enviar.conBotones(prefijo + "💳 " + pregunta, t.build()));
    }

    // --- Lo que dice el usuario ---

    /** "La tarjeta cierra el 26/11 y vence el 11/12". Si falta una de las dos, la pregunta. */
    List<Accion> fechas(long chatId, ValidadorInterpretacion.FechasTarjeta f, String prefijo) {
        return fechas(chatId, f, null, prefijo);
    }

    /** @param texto lo que escribió, para saber de qué tarjeta habla */
    List<Accion> fechas(long chatId, ValidadorInterpretacion.FechasTarjeta f, String texto, String prefijo) {
        return conTarjeta(chatId, texto, Para.FECHAS, "¿De qué tarjeta son esas fechas?", prefijo,
                tarjetaId -> fechas(chatId, tarjetaId, f, prefijo));
    }

    private List<Accion> fechas(long chatId, long tarjetaId, ValidadorInterpretacion.FechasTarjeta f,
            String prefijo) {
        if (f.cierre() == null || f.vencimiento() == null) {
            esperas.put(chatId, new EsperaFecha(tarjetaId, f.cierre(), f.vencimiento()));
            return List.of(Accion.Enviar.texto(prefijo + (f.cierre() == null
                    ? "💳 Vence el " + dia(f.vencimiento(), false) + ". ¿Y cuándo cierra?"
                    : "💳 Cierra el " + dia(f.cierre(), false) + ". ¿Y cuándo vence?")));
        }
        try {
            CicloTarjetaService.FechasCargadas cargadas = ciclo.cargarFechas(tarjetaId, f.cierre(), f.vencimiento());
            VistaResumenTarjeta r = cargadas.resumen();
            String antes = cargadas.antes() == null || cargadas.antes().confirmadas()
                    || (cargadas.antes().cierre().equals(r.cierre())
                            && cargadas.antes().vencimiento().equals(r.vencimiento())) ? ""
                    : "\n<i>Lo tenía estimado para el " + dia(cargadas.antes().cierre(), true) + " y el "
                            + dia(cargadas.antes().vencimiento(), true) + ": las compras ya quedaron en su resumen.</i>";
            return List.of(Accion.Enviar.conMenu(prefijo + "💳 Listo: el resumen" + deLaTarjeta(r)
                    + " cierra el " + dia(r.cierre(), false) + " y vence el " + dia(r.vencimiento(), false) + "."
                    + antes + "\nEn ese resumen llevás <b>" + formato.pesos(r.cargado()) + "</b>."));
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 No pude guardar esas fechas: " + html(e.getMessage())
                    + "."));
        }
    }

    /** "El resumen de la tarjeta es de 250.000": se compara con lo cargado; no crea gastos. */
    List<Accion> total(BigDecimal total, Long resumenId, String prefijo) {
        return total(0, total, resumenId, null, prefijo);
    }

    /** @param texto lo que escribió, para saber de qué tarjeta habla (si no se sabe el resumen) */
    List<Accion> total(long chatId, BigDecimal total, Long resumenId, String texto, String prefijo) {
        if (resumenId != null) {
            return cargarTotal(null, resumenId, total, prefijo);
        }
        return conTarjeta(chatId, texto, Para.RESUMEN_CERRADO, "¿De qué tarjeta es ese resumen?", prefijo,
                tarjetaId -> cargarTotal(tarjetaId, null, total, prefijo));
    }

    private List<Accion> cargarTotal(Long tarjetaId, Long resumenId, BigDecimal total, String prefijo) {
        try {
            CicloTarjetaService.TotalCargado t = ciclo.cargarTotal(tarjetaId, resumenId, total);
            return List.of(Accion.Enviar.conMenu(prefijo + textoTotal(t)));
        } catch (NoSuchElementException e) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 " + html(e.getMessage()) + "."));
        }
    }

    String textoTotal(CicloTarjetaService.TotalCargado t) {
        String encabezado = "🧾 Resumen" + deLaTarjeta(t.resumen()) + " que vence el "
                + dia(t.resumen().vencimiento(), t.resumen().estimadas()) + ": <b>" + formato.pesos(t.total())
                + "</b>.\n";
        if (t.coincide()) {
            return encabezado + "✅ Coincide con lo que cargaste.";
        }
        return encabezado + "⚠️ El resumen dice " + formato.pesos(t.total()) + " y cargaste "
                + formato.pesos(t.cargado()) + ": " + (t.diferencia().signum() > 0
                        ? "faltan <b>" + formato.pesos(t.diferencia()) + "</b> sin cargar."
                        : "cargaste <b>" + formato.pesos(t.diferencia().negate()) + "</b> de más.")
                + (t.resumen().dolaresEstimados() ? "\n💵 Ojo: lo cargado incluye " + formato.dolaresCompra(
                        t.resumen().dolares()) + " en compras en dólares con los pesos estimados (≈ " + formato.pesos(
                                t.resumen().dolaresEnPesos()) + "): parte de la diferencia puede venir de ahí. Cuando "
                        + "sepas cuánto te cobró cada una, decímelo." : "")
                + "\nNo agregué nada: revisalo cuando puedas.";
    }

    /** "\n💵 Compras en dólares: US$ 20 (≈ $30.000 · 1 sin estimar)", o "" si el resumen no tiene. */
    private String comprasEnDolares(VistaResumenTarjeta r) {
        if (r.dolares().signum() == 0) {
            return "";
        }
        int sinEstimar = r.dolaresSinEstimar();
        return "\n💵 Compras en dólares: " + formato.dolaresCompra(r.dolares()) + " (" + (r.dolaresEstimados() ? "≈ "
                : "= ") + formato.pesos(r.dolaresEnPesos()) + (sinEstimar == 0 ? ""
                        : " · " + sinEstimar + " sin estimar") + ")";
    }

    /** "Se debitó la tarjeta, 250.000": se confirma con un botón antes de registrar el pago. */
    List<Accion> pago(BigDecimal monto, String prefijo) {
        return pago(0, monto, null, prefijo);
    }

    /** "Pagué la tarjeta de mercado pago, 85.000": contra esa tarjeta, desde su cuenta. */
    List<Accion> pago(long chatId, BigDecimal monto, String texto, String prefijo) {
        return conTarjeta(chatId, texto, Para.RESUMEN_CERRADO, "¿De qué tarjeta fue el pago?", prefijo,
                tarjetaId -> pagoDe(tarjetaId, monto, prefijo));
    }

    private List<Accion> pagoDe(long tarjetaId, BigDecimal monto, String prefijo) {
        Optional<CicloTarjetaService.Panel> panel = ciclo.panel(tarjetaId);
        if (panel.isEmpty()) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 No existe esa tarjeta o está dada de baja."));
        }
        Optional<VistaResumenTarjeta> resumen = panel.get().porPagar().stream().findFirst();
        if (resumen.isEmpty()) {
            if (monto == null || monto.signum() <= 0) {
                return List.of(Accion.Enviar.texto(prefijo + "🤔 No tengo ningún resumen cerrado sin debitar"
                        + deLa(panel.get().tarjeta()) + ". Si ya lo pagaste, decime de cuánto fue."));
            }
            long centavos = monto.movePointRight(2).longValue();
            String cuenta = panel.get().cuentaDebito();
            return List.of(Accion.Enviar.conBotones(prefijo + "💳 No tengo ningún resumen cerrado sin debitar"
                    + deLa(panel.get().tarjeta()) + ". ¿Registro igual el pago?\n<b>" + formato.pesos(monto)
                    + "</b>" + (cuenta == null ? "" : " desde " + html(cuenta))
                    + ". No cuenta como gasto: las compras ya se contaron.",
                    Teclado.constructor().fila(new Teclado.Boton("✅ Sí, registrar", CONFIRMAR_PAGO + ":-" + tarjetaId
                            + ":" + centavos), new Teclado.Boton("❌ Cancelar", CANCELAR_PAGO + ":-" + tarjetaId))
                            .build()));
        }
        VistaResumenTarjeta r = resumen.get();
        BigDecimal debitado = monto != null ? monto : r.aDebitar();
        if (debitado.signum() <= 0) {
            return List.of(Accion.Enviar.texto(prefijo + "🤔 ¿De cuánto fue el débito?"));
        }
        long centavos = debitado.movePointRight(2).longValue();
        return List.of(Accion.Enviar.conBotones(prefijo + "💳 ¿Registro el débito del resumen" + deLaTarjeta(r)
                + " que vence el " + dia(r.vencimiento(), r.estimadas()) + "?\n<b>" + formato.pesos(debitado) + "</b>"
                + (r.cuentaDebito() == null ? "" : " desde " + html(r.cuentaDebito()))
                + ". No cuenta como gasto: las compras ya se contaron.",
                Teclado.constructor().fila(new Teclado.Boton("✅ Sí, registrar", CONFIRMAR_PAGO + ":" + r.id() + ":"
                        + centavos), new Teclado.Boton("❌ Cancelar", CANCELAR_PAGO + ":" + r.id())).build()));
    }

    // --- Avisos (los manda TarjetaProgramada) ---

    Accion.Enviar preguntaDeFechas(CicloTarjetaService.PreguntaFechas p) {
        Teclado teclado = Teclado.constructor()
                .fila(new Teclado.Boton("⏳ Todavía no", TODAVIA_NO_FECHAS + ":" + p.clave())).build();
        if (p.proximo() == null) {
            return Accion.Enviar.conBotones("💳 ¿Cuándo cierra y vence la <b>" + html(p.tarjeta()) + "</b>?\n"
                    + "Todavía no tengo ninguna fecha suya. Contame el último cierre y su vencimiento, como te salga "
                    + "(por ejemplo: <i>la " + html(p.tarjeta()) + " cerró el 29/10 y vence el 11/11</i>).", teclado);
        }
        VistaResumenTarjeta proximo = p.proximo();
        return Accion.Enviar.conBotones("💳 ¿Ya sabés el próximo cierre y vencimiento de " + laTarjeta(proximo)
                + "?\nPor ahora los estimo: cierra " + dia(proximo.cierre(), true) + " · vence "
                + dia(proximo.vencimiento(), true) + ".\nCuando los sepas, contámelos como te salga.", teclado);
    }


    Accion.Enviar avisoDeVencimiento(VistaResumenTarjeta r) {
        return Accion.Enviar.conBotones("💳 El " + dia(r.vencimiento(), r.estimadas()) + " vence "
                + laTarjeta(r) + ": "
                + (r.totalReal() == null ? "según lo cargado, <b>" + formato.pesos(r.cargado()) + "</b>."
                        : "<b>" + formato.pesos(r.totalReal()) + "</b> (lo cargado: " + formato.pesos(r.cargado())
                                + ").")
                + (r.cuentaDebito() == null ? "" : " Se debita de " + html(r.cuentaDebito()) + "."),
                Teclado.constructor().fila(new Teclado.Boton("🧾 Cargar total del resumen", CARGAR_TOTAL + ":"
                        + r.id())).build());
    }

    Accion.Enviar preguntaDeDebito(VistaResumenTarjeta r) {
        return Accion.Enviar.conBotones("💳 ¿Se debitó el resumen de " + laTarjeta(r) + " (<b>"
                + formato.pesos(r.aDebitar()) + "</b>)?", Teclado.constructor().fila(
                        new Teclado.Boton("✅ Sí", DEBITADO + ":" + r.id()),
                        new Teclado.Boton("⏳ Todavía no", TODAVIA_NO_DEBITO + ":" + r.id())).build());
    }

    // --- Botones ---

    /** @param id el resumen; con {@link #ELEGIR_TARJETA}, la tarjeta; negativo, −tarjeta sin resumen */
    List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido) {
        try {
            return switch (accion) {
                case TODAVIA_NO_FECHAS -> {
                    LocalDate hasta = ciclo.posponerFechas(id);
                    yield List.of(new Accion.Editar(mensajeId, "👌 Te vuelvo a preguntar el " + dia(hasta, false)
                            + ". Si las sabés antes, contámelas.", Teclado.NINGUNO));
                }
                case CARGAR_TOTAL -> {
                    esperas.put(chatId, new EsperaTotal(id));
                    yield List.of(new Accion.QuitarBotones(mensajeId),
                            Accion.Enviar.texto("🧾 ¿De cuánto es el resumen? Escribime el total."));
                }
                case DEBITADO -> pagar(mensajeId, id, null, Origen.AUTOMATICO);
                case CONFIRMAR_PAGO -> id < 0
                        ? pagarSinResumen(mensajeId, -id, elegido == null ? null : BigDecimal.valueOf(elegido, 2))
                        : pagar(mensajeId, id, elegido == null ? null : BigDecimal.valueOf(elegido, 2), Origen.TEXTO);
                case TODAVIA_NO_DEBITO -> List.of(new Accion.Editar(mensajeId,
                        "👌 Mañana te pregunto de nuevo si se debitó.", Teclado.NINGUNO));
                case CANCELAR_PAGO -> List.of(new Accion.Editar(mensajeId, "❌ No registré el débito.",
                        Teclado.NINGUNO), new Accion.Avisar("Cancelado"));
                case ELEGIR_TARJETA -> elegirTarjeta(chatId, mensajeId, id);
                default -> List.of(new Accion.Avisar("Botón inválido"));
            };
        } catch (IllegalStateException | IllegalArgumentException | NoSuchElementException e) {
            return List.of(new Accion.Editar(mensajeId, "🤔 " + html(e.getMessage()) + ".", Teclado.NINGUNO));
        }
    }

    private List<Accion> elegirTarjeta(long chatId, int mensajeId, long tarjetaId) {
        Function<Long, List<Accion>> accion = sinTarjeta.remove(chatId);
        if (accion == null) {
            return List.of(new Accion.QuitarBotones(mensajeId), new Accion.Avisar("Ya no sé qué era: decímelo de nuevo"));
        }
        String nombre = catalogos.cargar().tarjeta(tarjetaId).map(TarjetaCredito::getNombre).orElse("esa tarjeta");
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.Editar(mensajeId, "💳 " + html(nombre), Teclado.NINGUNO));
        acciones.addAll(accion.apply(tarjetaId));
        return acciones;
    }

    private List<Accion> pagar(int mensajeId, long resumenId, BigDecimal monto, Origen origen) {
        CicloTarjetaService.Pago pago = ciclo.pagar(null, resumenId, monto, origen, null);
        VistaResumenTarjeta r = pago.resumen();
        return List.of(new Accion.Editar(mensajeId, "✅ <b>Débito registrado</b>: " + formato.pesos(
                pago.movimiento().getMonto()) + (r.cuentaDebito() == null ? "" : " desde " + html(r.cuentaDebito()))
                + ".\nEl resumen" + deLaTarjeta(r) + " que vencía el " + dia(r.vencimiento(), false)
                + " quedó pagado. No cuenta como gasto.", Teclado.NINGUNO), new Accion.Avisar("✅ Registrado"));
    }

    private List<Accion> pagarSinResumen(int mensajeId, long tarjetaId, BigDecimal monto) {
        Movimiento pago = ciclo.pagarSinResumen(tarjetaId, monto, Origen.TEXTO, null);
        String cuenta = pago.getCuenta() == null ? null : pago.getCuenta().getNombre();
        return List.of(new Accion.Editar(mensajeId, "✅ <b>Pago" + deLa(pago.getTarjeta().getNombre())
                + " registrado</b>: " + formato.pesos(pago.getMonto()) + (cuenta == null ? "" : " desde " + html(cuenta))
                + ". No cuenta como gasto.", Teclado.NINGUNO), new Accion.Avisar("✅ Registrado"));
    }

    // --- Lo que se espera que escriba ---

    boolean esperando(long chatId) {
        return esperas.containsKey(chatId);
    }

    void olvidar(long chatId) {
        esperas.remove(chatId);
        sinTarjeta.remove(chatId);
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
                            .map(c -> fechas(chatId, f.tarjetaId(),
                                    new ValidadorInterpretacion.FechasTarjeta(c, f.vencimiento()), ""))
                    : ParserTarjeta.vencimiento(texto, f.cierre())
                            .map(v -> fechas(chatId, f.tarjetaId(),
                                    new ValidadorInterpretacion.FechasTarjeta(f.cierre(), v), ""));
            case null -> Optional.empty();
        };
    }

    /** Para buscar un cierre que se dijo después del vencimiento: hasta cuántos días antes puede estar. */
    private static final int CICLO_MAXIMO_DIAS = 40;

    /** " de la MasterCard Gold" con varias tarjetas, o "" con una sola (se sobreentiende). */
    private String deLaTarjeta(VistaResumenTarjeta r) {
        return r.tarjeta() == null || catalogos.cargar().tarjetas().size() <= 1 ? "" : deLa(r.tarjeta());
    }

    /** "la tarjeta" con una sola, o "la <b>MasterCard Gold</b>" con varias. */
    private String laTarjeta(VistaResumenTarjeta r) {
        return r.tarjeta() == null || catalogos.cargar().tarjetas().size() <= 1 ? "la tarjeta"
                : "la <b>" + html(r.tarjeta()) + "</b>";
    }

    private static String deLa(String tarjeta) {
        return tarjeta == null ? "" : " de la " + html(tarjeta);
    }

    /** "11/11", o "≈ 14/12" si es una estimación. */
    private static String dia(LocalDate dia, boolean estimada) {
        return (estimada ? "≈ " : "") + DIA_MES.format(dia);
    }
}
