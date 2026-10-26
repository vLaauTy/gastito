package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.audio.TranscripcionNoDisponibleException;
import ar.gastito.audio.TranscripcionService;
import ar.gastito.bot.Tarjetas.Campo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.consulta.ConsultaService;
import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.ia.ResumenUsoIa;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoIncompletoException;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.TicketService;

/**
 * La conversación con el usuario, sin depender de la API de Telegram: recibe textos y botones y devuelve
 * {@link Accion}es. Cada gasto es una sola tarjeta que se edita en el lugar.
 */
@Component
public class ProcesadorMensajes {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorMensajes.class);

    static final int LARGO_MAXIMO_DESCRIPCION = 255;

    static final String BIENVENIDA = """
            ¡Hola! Soy <b>Gastito</b> 👋
            Mandame tus gastos como si me escribieras por chat, por ejemplo:

            • kiosco 1500
            • 3700 en pan de pancho y papas, efectivo
            • pagué 12000 de nafta con la tarjeta

            También me lo podés decir en un audio 🎙 o mandarme la foto del ticket 🧾
            Te muestro lo que entendí y lo guardás con un toque.""";

    static final String AYUDA = """
            <b>Cómo cargar un gasto</b>
            • Lo más rápido: categoría y monto, en cualquier orden ("kiosco 1500", "1500 super"). Podés sumar el medio de pago ("super 8000 débito").
            • Si escribís algo más largo ("ayer 3700 en pan y papas con QR"), lo interpreta la IA.
            • Montos a la argentina: 1.500 o 1500,50.
            • Fechas: "ayer", "anteayer", "el sábado", "12/10".
            • Por audio 🎙: decilo como lo escribirías ("gasto 3700 en pan y papas, efectivo"), un gasto por audio. Arriba de la tarjeta te muestro lo que escuché.
            • Con la foto del ticket 🧾: leo el comercio, los productos y el total. Si querés, escribí algo con la foto ("efectivo", "con la de Galicia"). Un ticket por foto, entero y sin reflejos.

            <b>La tarjeta</b>
            Te muestro lo que entendí. Si no estoy seguro de la categoría, te la pregunto con botones. Con ✏️ Editar cambiás cualquier dato antes de guardar.
            Aprendo de lo que guardás: la próxima vez que cargues algo parecido, uso la misma categoría.

            <b>Ingresos</b>
            /sueldo 380.000 galicia
            /aguinaldo 190 mil galicia
            /ingreso 25000 arreglo de PC a mercado pago (cualquier otro ingreso: una venta, un regalo…)
            Si no decís la cuenta, uso la del último ingreso de ese tipo. Te muestro la tarjeta y lo guardás con un toque.

            <b>Juntadas</b>
            "pagué 48000 el asado, éramos 6, con mercado pago": en tus gastos cuenta solo tu parte y el resto queda a cobrar. Cuando alguien te paga, decímelo: "Pablo me pasó 8000". Con /juntadas ves quién te debe.

            <b>Gastos fijos</b>
            "/fijo seguro 32900 tarjeta día 4" (o "agregá un fijo: …"). El día del cobro te pregunto si se cobró; la luz y el gas, con el promedio de los últimos cobros. Con /fijos los ves y los das de baja.

            <b>Preguntas</b>
            Preguntame por escrito o por audio: "¿cuánto gasté en kiosco este mes?", "compará octubre con septiembre", "¿cuántas Seven Up compré?", "¿cuánto me queda disponible?". Los números los calculo con tu base, no los inventa la IA.

            <b>Resúmenes</b>
            El domingo a las 21 te mando cómo fue tu semana, y el día 1 a las 9, cómo fue el mes. Si la PC estaba apagada, te llega apenas la prendés.

            <b>Comandos</b>
            /hoy – gastos de hoy y el total
            /mes – total del mes por categoría
            /resumen – tu semana hasta hoy, con lo que noté; /resumen mes, el mes
            /juntadas – quién te debe plata de las juntadas
            /fijo – agregar un gasto fijo; /fijos, los que tenés
            /costos – lo gastado en IA este mes
            /dashboard – el link del dashboard, para ver tus gastos con gráficos
            /ayuda – este mensaje""";

    private final InterpretadorService interpretador;
    private final MovimientoService movimientos;
    private final CatalogoService catalogos;
    private final RegistroUsoIa usoIa;
    private final ResolvedorFechas fechas;
    private final Tarjetas tarjetas;
    private final FormatoMensajes formato;
    private final GraficoMes grafico;
    private final ComandosIngreso comandosIngreso;
    private final TranscripcionService transcripciones;
    private final TicketService tickets;
    private final ResumenService resumenes;
    private final ConversacionRepetidos repetidos;
    private final ConversacionJuntadas juntadas;
    private final ConversacionFijos fijos;
    private final FormatoResumen formatoResumen;
    private final ConsultaService consultas;
    private final FormatoConsulta formatoConsulta;

    /** Botones de un mensaje dudoso: "dg:<id>" (es un gasto) y "dp:<id>" (es una pregunta). */
    static final String ES_GASTO = "dg";
    static final String ES_PREGUNTA = "dp";
    /** Cuántos mensajes dudosos se recuerdan esperando un botón (los más viejos se olvidan). */
    static final int DUDOSOS_RECORDADOS = 20;

    /** Un mensaje que puede ser un gasto o una pregunta, esperando que el usuario elija. */
    private record Dudoso(InterpretadorService.Interpretado gasto, String texto, Origen origen) {
    }

    private final AtomicLong siguienteDudoso = new AtomicLong();
    private final Map<Long, Dudoso> dudosos = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Dudoso> mas) {
            return size() > DUDOSOS_RECORDADOS;
        }
    });
    private final Duration duracionMaximaAudio;
    private final String urlDashboard;
    private final Clock clock;

    /** Por chat: el campo de qué tarjeta espera que el usuario escriba (o diga) el valor nuevo. */
    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();

    /**
     * @param mensajeId la tarjeta en el chat; null hasta que Telegram confirma el envío
     * @param propuesta el valor que se entendió de un audio, esperando que el usuario lo aplique; o null
     */
    private record Espera(long movimientoId, Campo campo, Integer mensajeId, Valor propuesta) {

        Espera(long movimientoId, Campo campo, Integer mensajeId) {
            this(movimientoId, campo, mensajeId, null);
        }
    }

    /** Un valor ya leído para un campo de la tarjeta. */
    private sealed interface Valor {
        record Monto(BigDecimal monto) implements Valor {
        }

        record Fecha(LocalDateTime fechaHora) implements Valor {
        }

        record Descripcion(String descripcion) implements Valor {
        }
    }

    public ProcesadorMensajes(InterpretadorService interpretador, MovimientoService movimientos,
            CatalogoService catalogos, RegistroUsoIa usoIa, ResolvedorFechas fechas, Tarjetas tarjetas,
            FormatoMensajes formato, GraficoMes grafico, ComandosIngreso comandosIngreso,
            TranscripcionService transcripciones, TicketService tickets, ResumenService resumenes,
            FormatoResumen formatoResumen, ConsultaService consultas, FormatoConsulta formatoConsulta,
            ConversacionRepetidos repetidos, ConversacionJuntadas juntadas, ConversacionFijos fijos,
            GastitoProperties properties, DashboardProperties dashboard, Clock clock) {
        this.fijos = fijos;
        this.repetidos = repetidos;
        this.juntadas = juntadas;
        this.consultas = consultas;
        this.formatoConsulta = formatoConsulta;
        this.tickets = tickets;
        this.resumenes = resumenes;
        this.formatoResumen = formatoResumen;
        this.grafico = grafico;
        this.comandosIngreso = comandosIngreso;
        this.interpretador = interpretador;
        this.movimientos = movimientos;
        this.catalogos = catalogos;
        this.usoIa = usoIa;
        this.fechas = fechas;
        this.tarjetas = tarjetas;
        this.formato = formato;
        this.transcripciones = transcripciones;
        this.duracionMaximaAudio = properties.audio().duracionMaxima();
        this.urlDashboard = dashboard.url();
        this.clock = clock;
    }

    // --- Mensajes de texto ---

    /**
     * @param mensajeId el mensaje del usuario (se borra si era el valor de un campo que se estaba editando)
     */
    public List<Accion> procesarTexto(long chatId, int mensajeId, String texto) {
        String limpio = texto == null ? "" : texto.trim();
        String comando = comando(limpio);
        List<Accion> acciones = new ArrayList<>();

        // El cobro de un fijo esperaba el monto ("Cambió el monto").
        if (fijos.esperando(chatId)) {
            if (comando == null) {
                return fijos.texto(chatId, limpio);
            }
            fijos.olvidar(chatId);
        }

        // La juntada esperaba un nombre o la parte de alguien.
        if (juntadas.esperando(chatId)) {
            if (comando == null) {
                return juntadas.texto(chatId, limpio);
            }
            juntadas.olvidar(chatId);
        }

        Espera espera = esperas.remove(chatId);
        if (espera != null) {
            if (comando == null) {
                Optional<List<Accion>> aplicado = aplicar(chatId, espera, limpio, mensajeId);
                if (aplicado.isPresent()) {
                    return aplicado.get();
                }
            }
            // No era un valor para el campo: la tarjeta vuelve a su estado y el mensaje se procesa normal.
            acciones.addAll(restaurar(espera));
        }

        if ("/resumen".equals(comando)) {
            acciones.add(resumen(limpio));
        } else if ("/juntadas".equals(comando)) {
            acciones.addAll(juntadas.abiertas());
        } else if ("/fijo".equals(comando)) {
            acciones.add(fijos.alta(limpio.replaceFirst("^/\\S+\\s*", "")));
        } else if ("/fijos".equals(comando)) {
            acciones.addAll(fijos.lista());
        } else if (comando != null) {
            ClaseIngreso clase = ComandosIngreso.clase(comando);
            acciones.add(clase != null ? comandosIngreso.responder(clase, limpio) : responderComando(comando));
        } else {
            acciones.add(nuevoGasto(chatId, limpio, Origen.TEXTO));
        }
        return acciones;
    }

    /** Telegram confirmó el envío de la tarjeta de un gasto: si se espera un valor para ella, se anota dónde está. */
    public void tarjetaEnviada(long chatId, long movimientoId, int mensajeId) {
        esperas.computeIfPresent(chatId, (chat, e) -> e.movimientoId() == movimientoId && e.mensajeId() == null
                ? new Espera(movimientoId, e.campo(), mensajeId, e.propuesta())
                : e);
    }

    // --- Audios ---

    /**
     * Se transcribe y sigue el mismo camino que un texto. Si la tarjeta espera el valor de un campo y lo que se
     * escuchó sirve para ese campo, se propone (con Aplicar y Cancelar edición) en vez de aplicarlo directo.
     * Fuera de la edición, un audio siempre es un gasto nuevo.
     *
     * @param mensajeId el audio del usuario (se borra si era el valor de un campo)
     */
    public List<Accion> procesarAudio(long chatId, int mensajeId, AudioRecibido audio) {
        if (audio.duracionSegundos() > duracionMaximaAudio.toSeconds()) {
            return List.of(Accion.Enviar.texto("""
                    El audio dura %d segundos y el máximo es %d 🙏
                    Mandame uno más corto, con un gasto por audio."""
                    .formatted(audio.duracionSegundos(), duracionMaximaAudio.toSeconds())));
        }
        String escuchado;
        try {
            escuchado = limpiarTranscripcion(transcripciones.transcribir(audio.descarga().descargar(),
                    audio.nombreArchivo()));
        } catch (IOException e) {
            log.warn("No se pudo descargar el audio de Telegram ({})", e.getClass().getSimpleName());
            return List.of(Accion.Enviar.texto("No pude descargar el audio de Telegram 😕 Probá mandarlo de nuevo."));
        } catch (TranscripcionNoDisponibleException e) {
            return List.of(Accion.Enviar.texto(sinTranscripcion(e.getMotivo())));
        }
        if (escuchado.isEmpty()) {
            return List.of(Accion.Enviar.texto("""
                    No escuché nada en el audio 🤔
                    Probá de nuevo hablando cerca del micrófono, o mandámelo escrito."""));
        }

        List<Accion> acciones = new ArrayList<>();
        Espera espera = esperas.remove(chatId);
        if (espera != null) {
            Optional<List<Accion>> propuesta = proponer(chatId, espera, escuchado, mensajeId);
            if (propuesta.isPresent()) {
                return propuesta.get();
            }
            acciones.addAll(restaurar(espera));
        }
        acciones.add(nuevoGasto(chatId, escuchado, Origen.AUDIO));
        return acciones;
    }

    // --- Fotos de tickets ---

    /**
     * Se lee el ticket y queda la tarjeta de siempre, con los productos. Una foto no es el valor de un campo: si
     * la tarjeta esperaba uno, vuelve a su estado normal.
     *
     * @param mensajeId la foto del usuario
     */
    public List<Accion> procesarFoto(long chatId, int mensajeId, FotoRecibida foto) {
        List<Accion> acciones = new ArrayList<>();
        Espera espera = esperas.remove(chatId);
        if (espera != null) {
            acciones.addAll(restaurar(espera));
        }
        acciones.add(nuevoTicket(foto));
        return acciones;
    }

    private Accion nuevoTicket(FotoRecibida foto) {
        if (foto.bytes() != null && tickets.superaElTamanio(foto.bytes())) {
            return Accion.Enviar.texto(demasiadoGrande());
        }
        TicketService.Resultado resultado;
        try {
            resultado = tickets.leer(foto.descarga().descargar(), foto.texto());
        } catch (IOException e) {
            log.warn("No se pudo descargar la foto de Telegram ({})", e.getClass().getSimpleName());
            return Accion.Enviar.texto("No pude descargar la foto de Telegram 😕 Probá mandarla de nuevo.");
        } catch (ImagenInvalidaException e) {
            return Accion.Enviar.texto(switch (e.getMotivo()) {
                case DEMASIADO_GRANDE -> demasiadoGrande();
                case FORMATO_NO_SOPORTADO -> """
                        No pude abrir esa imagen 😕
                        Mandala como foto (no como archivo), o en JPG o PNG.""";
            });
        } catch (IaNoDisponibleException e) {
            return Accion.Enviar.texto("""
                    No pude leer el ticket: %s.
                    Mientras tanto escribime el gasto, por ejemplo: super 8050"""
                    .formatted(html(e.getMessage())));
        }
        return switch (resultado) {
            case TicketService.Leido l -> tarjetaNueva(l.movimiento());
            case TicketService.NoEsTicket n -> Accion.Enviar.texto("""
                    Esa foto no parece un ticket 🤔
                    Mandame la foto de un ticket o una factura de compra, o escribime el gasto ("super 8050").""");
            case TicketService.Ilegible i -> Accion.Enviar.texto(switch (i.motivo()) {
                case BORROSA -> """
                        No pude leer bien el ticket: la foto salió borrosa 📷
                        Sacala de nuevo con buena luz, con el ticket entero y sin reflejos. Si es muy largo, sacalo en dos partes y mandá una foto por parte.""";
                case CORTADA, SIN_TOTAL -> """
                        No encontré el total del ticket 🤔 No guardé nada.
                        Mandame una foto más clara, con el ticket entero (que se vea el TOTAL) y sin reflejos.""";
            });
        };
    }

    private String demasiadoGrande() {
        return """
                La imagen pesa más de %d MB 🙏
                Mandala como foto (Telegram la achica sola) o en un archivo más liviano."""
                .formatted(tickets.tamanioMaximo().toMegabytes());
    }

    /** Whisper suele cerrar con un punto ("Kiosco 1500."), que no es parte del dato. */
    static String limpiarTranscripcion(String texto) {
        return texto == null ? "" : texto.strip().replaceAll("[.。!¡?¿…]+$", "").strip();
    }

    private static String sinTranscripcion(TranscripcionNoDisponibleException.Motivo motivo) {
        return switch (motivo) {
            case SIN_RESPUESTA -> "No pude transcribir el audio, ¿la VM está prendida? Mientras tanto mandámelo escrito.";
            case CLAVE_RECHAZADA -> """
                    No pude transcribir el audio: el servidor de Whisper rechazó la clave.
                    Revisá WHISPER_API_KEY en el .env. Mientras tanto mandámelo escrito.""";
            case ERROR_DEL_SERVIDOR -> """
                    No pude transcribir el audio: el servidor de Whisper respondió con un error.
                    Mientras tanto mandámelo escrito.""";
        };
    }

    /** Muestra el valor que se escuchó para el campo en edición, sin aplicarlo. Vacío si no sirve para ese campo. */
    private Optional<List<Accion>> proponer(long chatId, Espera espera, String escuchado, int mensajeUsuario) {
        Optional<Valor> valor = leer(espera.campo(), escuchado);
        if (valor.isEmpty()) {
            return Optional.empty();
        }
        Movimiento m;
        try {
            m = pendiente(espera.movimientoId());
        } catch (MovimientoNoPendienteException | NoSuchElementException e) {
            return Optional.empty();
        }
        esperas.put(chatId, new Espera(m.getId(), espera.campo(), espera.mensajeId(), valor.get()));
        String html = tarjetas.propuesta(m, escuchado, espera.campo(), formatear(valor.get()));
        Teclado teclado = tarjetas.propuesta(m.getId());
        return Optional.of(List.of(new Accion.Borrar(mensajeUsuario), espera.mensajeId() != null
                ? new Accion.Editar(espera.mensajeId(), html, teclado)
                : Accion.Enviar.tarjeta(html, teclado, m.getId())));
    }

    private String formatear(Valor valor) {
        return switch (valor) {
            case Valor.Monto v -> formato.pesos(v.monto());
            case Valor.Fecha v -> formato.fecha(v.fechaHora());
            case Valor.Descripcion v -> v.descripcion();
        };
    }

    // --- Gasto nuevo y edición de campos ---

    private Accion nuevoGasto(long chatId, String texto, Origen origen) {
        boolean audio = origen == Origen.AUDIO;
        InterpretadorService.Resultado resultado;
        try {
            resultado = interpretador.interpretar(texto, audio ? PropositoIa.AUDIO : PropositoIa.TEXTO);
        } catch (IaNoDisponibleException e) {
            return Accion.Enviar.texto((audio ? escuche(texto) : "") + """
                    No pude interpretar el mensaje: %s.
                    Mientras tanto podés usar el formato simple, por ejemplo: kiosco 1500"""
                    .formatted(html(e.getMessage())));
        }
        return switch (resultado) {
            case InterpretadorService.Interpretado i -> {
                Movimiento m = crearGasto(i, origen, texto);
                if (m.getMonto() == null) {
                    esperas.put(chatId, new Espera(m.getId(), Campo.MONTO, null));
                    yield Accion.Enviar.tarjeta(tarjetas.pidiendoMontoFaltante(m), tarjetas.principal(m), m.getId());
                }
                yield tarjetaNueva(m);
            }
            case InterpretadorService.Pregunta _ -> Accion.Enviar.conMenu(responderPregunta(texto, audio));
            case InterpretadorService.Dudoso d -> {
                long id = siguienteDudoso.incrementAndGet();
                dudosos.put(id, new Dudoso(d.comoGasto(), texto, origen));
                yield Accion.Enviar.conBotones((audio ? escuche(texto) : "")
                        + "🤔 ¿Es un gasto para cargar o una pregunta sobre tus gastos?", Teclado.constructor()
                                .fila(new Teclado.Boton("🧾 Es un gasto", ES_GASTO + ":" + id),
                                        new Teclado.Boton("❓ Es una pregunta", ES_PREGUNTA + ":" + id))
                                .build());
            }
            case InterpretadorService.Devolucion d ->
                    juntadas.devolucion(d.datos(), origen, texto, audio ? escuche(texto) : "");
            case InterpretadorService.AltaFijo a -> fijos.tarjetaDeAlta(a.pedido(), audio ? escuche(texto) : "");
            case InterpretadorService.NoEsGasto _ -> Accion.Enviar.texto(audio
                    ? escuche(texto) + """
                            No encontré un gasto en lo que escuché 🤔
                            Probá de nuevo, por ejemplo: "kiosco 1500", o mandámelo escrito."""
                    : """
                            No encontré un gasto en el mensaje 🤔
                            Probá con algo como "kiosco 1500" o mirá /ayuda""");
        };
    }

    private Movimiento crearGasto(InterpretadorService.Interpretado i, Origen origen, String texto) {
        Movimiento m = movimientos.crearPendiente(i.movimiento(), origen, texto);
        if (i.usoIaId() != null) {
            usoIa.asociarMovimiento(i.usoIaId(), m.getId());
        }
        // Una juntada sin monto queda como gasto común: sin el total no hay nada que repartir.
        return i.juntada() != null && m.getMonto() != null ? juntadas.crear(m, i.juntada()) : m;
    }

    // --- Preguntas ---

    /** Responde con tool use. Si la IA no está, lo dice; si inventó números, quedan los que calculó Java. */
    private String responderPregunta(String texto, boolean audio) {
        String prefijo = audio ? escuche(texto) : "";
        try {
            return prefijo + formatoConsulta.respuesta(consultas.responder(texto));
        } catch (IaNoDisponibleException e) {
            return prefijo + "No pude responder la pregunta: " + html(e.getMessage()) + ".";
        }
    }

    /** "Es un gasto": se crea la tarjeta con lo que ya se había interpretado. */
    private List<Accion> esUnGasto(long chatId, int mensajeId, long id) {
        Dudoso dudoso = dudosos.remove(id);
        if (dudoso == null) {
            return List.of(new Accion.Avisar("Ese mensaje ya no está pendiente"));
        }
        Movimiento m = crearGasto(dudoso.gasto(), dudoso.origen(), dudoso.texto());
        return List.of(m.getMonto() == null ? principal(chatId, mensajeId, m)
                : repetidos.siEsRepetido(m, mensajeId).orElseGet(() -> principal(chatId, mensajeId, m)));
    }

    /** La tarjeta de un gasto recién creado con monto: la de siempre, o la de "¿ya lo tenías registrado?". */
    private Accion.Enviar tarjetaNueva(Movimiento m) {
        return repetidos.siEsRepetido(m)
                .orElseGet(() -> Accion.Enviar.tarjeta(tarjetas.pendiente(m), tarjetas.principal(m), m.getId()));
    }

    /** "Es una pregunta": la respuesta reemplaza a los botones. */
    private List<Accion> esUnaPregunta(int mensajeId, long id) {
        Dudoso dudoso = dudosos.remove(id);
        if (dudoso == null) {
            return List.of(new Accion.Avisar("Ese mensaje ya no está pendiente"));
        }
        return List.of(new Accion.Editar(mensajeId,
                responderPregunta(dudoso.texto(), dudoso.origen() == Origen.AUDIO), Teclado.NINGUNO));
    }

    private static String escuche(String texto) {
        return "🎙 Escuché: \"" + html(texto) + "\"\n\n";
    }

    /** Aplica el valor que escribió el usuario. Vacío si no sirve para ese campo. */
    private Optional<List<Accion>> aplicar(long chatId, Espera espera, String texto, int mensajeUsuario) {
        Optional<Valor> valor = leer(espera.campo(), texto);
        if (valor.isEmpty()) {
            return Optional.empty();
        }
        Movimiento m;
        try {
            m = aplicar(espera.movimientoId(), valor.get());
        } catch (MovimientoNoPendienteException | NoSuchElementException e) {
            return Optional.empty();
        }
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.Borrar(mensajeUsuario));
        if (espera.mensajeId() != null) {
            acciones.add(principal(chatId, espera.mensajeId(), m));
        } else {
            acciones.add(Accion.Enviar.tarjeta(tarjetas.pendiente(m), tarjetas.principal(m), m.getId()));
        }
        return Optional.of(acciones);
    }

    /** Lee lo que escribió o dijo el usuario como valor del campo. Vacío si no sirve. */
    private Optional<Valor> leer(Campo campo, String texto) {
        return switch (campo) {
            case MONTO -> Montos.parsear(texto.replaceAll("(?i)\\s*pesos$", "")).map(Valor.Monto::new);
            case FECHA -> fechas.resolverTexto(texto, LocalDateTime.now(clock)).map(Valor.Fecha::new);
            case DESCRIPCION -> {
                if (texto.isEmpty()) {
                    yield Optional.empty();
                }
                String descripcion = texto.substring(0, 1).toUpperCase() + texto.substring(1);
                yield Optional.of(new Valor.Descripcion(descripcion.length() > LARGO_MAXIMO_DESCRIPCION
                        ? descripcion.substring(0, LARGO_MAXIMO_DESCRIPCION)
                        : descripcion));
            }
        };
    }

    private Movimiento aplicar(long movimientoId, Valor valor) {
        return switch (valor) {
            // En una juntada, el monto de la tarjeta es el total pagado: se reparte de nuevo.
            case Valor.Monto v -> juntadas.esJuntada(movimientoId)
                    ? juntadas.cambiarTotal(movimientoId, v.monto())
                    : movimientos.cambiarMonto(movimientoId, v.monto());
            case Valor.Fecha v -> movimientos.cambiarFechaHora(movimientoId, v.fechaHora());
            case Valor.Descripcion v -> movimientos.cambiarDescripcion(movimientoId, v.descripcion());
        };
    }

    /** Vuelve la tarjeta que esperaba un valor a su estado normal. */
    private List<Accion> restaurar(Espera espera) {
        if (espera.mensajeId() == null) {
            return List.of();
        }
        try {
            Movimiento m = movimientos.buscar(espera.movimientoId());
            if (m.isPendiente()) {
                return List.of(new Accion.Editar(espera.mensajeId(), tarjetas.pendiente(m), tarjetas.principal(m)));
            }
        } catch (NoSuchElementException e) {
            log.debug("La tarjeta a restaurar ya no existe: {}", espera.movimientoId());
        }
        return List.of();
    }

    // --- Botones ---

    /** @param mensajeId la tarjeta donde está el botón: se edita en el lugar */
    public List<Accion> procesarBoton(long chatId, int mensajeId, String datos) {
        FormatoResumen.Anteriores anteriores = FormatoResumen.leerAnteriores(datos);
        if (anteriores != null) {
            return verAnteriores(mensajeId, anteriores);
        }
        String[] partes = datos == null ? new String[0] : datos.split(":");
        Long id = partes.length >= 2 ? numero(partes[1]) : null;
        Long elegido = partes.length >= 3 ? numero(partes[2]) : null;
        if (id == null || (partes.length >= 3 && elegido == null)) {
            return List.of(new Accion.Avisar("Botón inválido"));
        }

        List<Accion> acciones = new ArrayList<>();
        juntadas.olvidar(chatId);
        fijos.olvidar(chatId);
        Espera previa = esperas.remove(chatId);
        if (previa != null && (previa.mensajeId() == null || previa.mensajeId() != mensajeId)) {
            acciones.addAll(restaurar(previa));
        }
        try {
            acciones.addAll(Tarjetas.APLICAR.equals(partes[0])
                    ? aplicarPropuesta(chatId, mensajeId, id, previa)
                    : boton(chatId, mensajeId, partes[0], id, elegido));
        } catch (MovimientoNoPendienteException e) {
            Movimiento m = e.getMovimiento();
            acciones.add(new Accion.Editar(mensajeId, tarjetas.terminado(m), Teclado.NINGUNO));
            acciones.add(new Accion.Avisar(m.getEstado() == EstadoMovimiento.CONFIRMADO
                    ? "Ese gasto ya estaba guardado"
                    : "Ese gasto ya estaba cancelado"));
        } catch (MovimientoIncompletoException e) {
            acciones.add(principal(chatId, mensajeId, movimientos.buscar(id)));
            acciones.add(new Accion.Avisar("Antes de guardar falta elegir la categoría o poner el monto"));
        } catch (NoSuchElementException e) {
            log.warn("Botón para algo que no existe: {}", datos);
            acciones.add(new Accion.Avisar("No encontré ese gasto"));
        }
        return acciones;
    }

    private List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido) {
        return switch (accion) {
            case Tarjetas.GUARDAR -> {
                MovimientoService.Confirmacion c = movimientos.confirmar(id);
                if (c.movimiento().isJuntada()) {
                    yield juntadas.guardada(mensajeId, c);
                }
                yield List.of(new Accion.Editar(mensajeId, tarjetas.guardado(c.movimiento(), c.totalMesCategoria()),
                        Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
            }
            case Tarjetas.CANCELAR -> {
                Movimiento cancelado = movimientos.cancelar(id);
                if (cancelado.getOrigen() == Origen.TICKET) {
                    tickets.borrarFoto(cancelado);
                }
                yield List.of(new Accion.Editar(mensajeId, tarjetas.cancelado(cancelado), Teclado.NINGUNO),
                        new Accion.Avisar("Cancelado"));
            }
            case Tarjetas.VOLVER -> List.of(principal(chatId, mensajeId, pendiente(id)));
            case Tarjetas.EDITAR -> List.of(
                    new Accion.Editar(mensajeId, tarjetas.pendiente(pendiente(id)), tarjetas.edicion(id)));
            case Tarjetas.OTRA_CATEGORIA -> List.of(new Accion.Editar(mensajeId, tarjetas.pendiente(pendiente(id)),
                    tarjetas.categorias(id, catalogos.cargar().categorias())));
            case Tarjetas.MEDIOS -> List.of(new Accion.Editar(mensajeId, tarjetas.pendiente(pendiente(id)),
                    tarjetas.medios(id, catalogos.cargar().mediosPago())));
            case Tarjetas.CATEGORIA -> List.of(principal(chatId, mensajeId,
                    movimientos.elegirCategoria(id, requerido(elegido))));
            case Tarjetas.MEDIO -> {
                long medio = requerido(elegido);
                yield List.of(principal(chatId, mensajeId,
                        movimientos.cambiarMedioPago(id, medio == Tarjetas.SIN_MEDIO ? null : medio)));
            }
            case Tarjetas.EDITAR_MONTO -> pedir(chatId, mensajeId, id, Campo.MONTO);
            case Tarjetas.EDITAR_FECHA -> pedir(chatId, mensajeId, id, Campo.FECHA);
            case Tarjetas.EDITAR_DESCRIPCION -> pedir(chatId, mensajeId, id, Campo.DESCRIPCION);
            case Tarjetas.JUNTADA_NOMBRES, Tarjetas.JUNTADA_PARTE, Tarjetas.JUNTADA_PERDIDO, Tarjetas.JUNTADA_MENSAJE,
                    Tarjetas.JUNTADA_VER, Tarjetas.DEVOLUCION_DE, Tarjetas.DEVOLUCION_QUEDA_DEBIENDO,
                    Tarjetas.DEVOLUCION_SALDADO -> juntadas.boton(chatId, mensajeId, accion, id, elegido);
            case ConversacionFijos.GUARDAR, ConversacionFijos.CANCELAR, ConversacionFijos.CATEGORIA,
                    ConversacionFijos.BAJA -> fijos.boton(mensajeId, accion, id, elegido);
            case ConversacionFijos.CONFIRMAR_COBRO, ConversacionFijos.CAMBIO_EL_MONTO, ConversacionFijos.ESTE_MES_NO ->
                    fijos.botonDeCobro(chatId, mensajeId, accion, id);
            case Tarjetas.UNIR -> repetidos.unir(mensajeId, id, requerido(elegido));
            case Tarjetas.DE_NUEVO -> repetidos.deNuevo(mensajeId, id)
                    .orElseGet(() -> List.of(principal(chatId, mensajeId, pendiente(id))));
            case ES_GASTO -> esUnGasto(chatId, mensajeId, id);
            case ES_PREGUNTA -> esUnaPregunta(mensajeId, id);
            default -> List.of(new Accion.Avisar("Botón inválido"));
        };
    }

    /** Aplica el valor que se entendió de un audio y la tarjeta vuelve a su estado normal. */
    private List<Accion> aplicarPropuesta(long chatId, int mensajeId, long id, Espera espera) {
        if (espera == null || espera.propuesta() == null || espera.movimientoId() != id) {
            return List.of(principal(chatId, mensajeId, pendiente(id)),
                    new Accion.Avisar("Ese cambio ya no está pendiente"));
        }
        return List.of(principal(chatId, mensajeId, aplicar(id, espera.propuesta())), new Accion.Avisar("✅ Aplicado"));
    }

    /** La tarjeta en su estado normal. Si todavía falta el monto, queda esperando que el usuario lo escriba. */
    private Accion.Editar principal(long chatId, int mensajeId, Movimiento m) {
        if (m.getMonto() == null) {
            esperas.put(chatId, new Espera(m.getId(), Campo.MONTO, mensajeId));
            return new Accion.Editar(mensajeId, tarjetas.pidiendoMontoFaltante(m), tarjetas.principal(m));
        }
        return new Accion.Editar(mensajeId, tarjetas.pendiente(m), tarjetas.principal(m));
    }

    private List<Accion> pedir(long chatId, int mensajeId, long id, Campo campo) {
        Movimiento m = pendiente(id);
        esperas.put(chatId, new Espera(id, campo, mensajeId));
        return List.of(new Accion.Editar(mensajeId, tarjetas.pidiendo(m, campo), tarjetas.soloVolver(id)));
    }

    private Movimiento pendiente(long id) {
        Movimiento m = movimientos.buscar(id);
        if (!m.isPendiente()) {
            throw new MovimientoNoPendienteException(m);
        }
        return m;
    }

    private static long requerido(Long elegido) {
        if (elegido == null) {
            throw new NoSuchElementException("Falta el id elegido");
        }
        return elegido;
    }

    private static Long numero(String texto) {
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Resúmenes ---

    /** "/resumen": la semana en curso hasta hoy; "/resumen mes": el mes en curso hasta hoy. */
    private Accion resumen(String texto) {
        String[] palabras = texto.strip().split("\\s+");
        TipoResumen tipo = palabras.length > 1 && palabras[1].toLowerCase().startsWith("mes")
                ? TipoResumen.MENSUAL
                : TipoResumen.SEMANAL;
        ResumenService.Armado armado = resumenes.pedido(tipo);
        return Accion.Enviar.conMenu(formatoResumen.mensaje(armado, 0));
    }

    /** "Ver anteriores": los resúmenes que quedaron pendientes con la PC apagada. El botón se saca. */
    private List<Accion> verAnteriores(int mensajeId, FormatoResumen.Anteriores anteriores) {
        List<ResumenService.Armado> armados = resumenes.anteriores(anteriores.tipo(), anteriores.desde(),
                anteriores.hasta());
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.QuitarBotones(mensajeId));
        if (armados.isEmpty()) {
            acciones.add(new Accion.Avisar("Ya los viste"));
            return acciones;
        }
        armados.forEach(a -> acciones.add(Accion.Enviar.texto(formatoResumen.mensaje(a, 0))));
        return acciones;
    }

    // --- Comandos ---

    /** "/hoy@gastito_bot extra" → "/hoy"; un botón del menú fijo → su comando. Null si no es un comando. */
    static String comando(String texto) {
        String delMenu = MenuFijo.comando(texto);
        if (delMenu != null) {
            return delMenu;
        }
        if (!texto.startsWith("/")) {
            return null;
        }
        return texto.split("\\s+")[0].split("@")[0].toLowerCase();
    }

    /** Las respuestas a comandos van con el menú fijo, así queda siempre visible. */
    private Accion responderComando(String comando) {
        return Accion.Enviar.conMenu(switch (comando) {
            case "/start" -> BIENVENIDA;
            case "/ayuda", "/help" -> AYUDA;
            case "/hoy" -> hoy();
            case "/mes" -> mes();
            case "/costos" -> costos();
            case "/dashboard" -> """
                    🖥 <b>Dashboard</b>: <a href="%1$s">%1$s</a>
                    Se abre desde el navegador de la PC donde corre Gastito.""".formatted(html(urlDashboard));
            default -> "No conozco ese comando. Mirá /ayuda";
        });
    }

    private String hoy() {
        MovimientoService.ResumenDia hoy = movimientos.hoy();
        if (hoy.gastos().isEmpty()) {
            return "Hoy no registraste gastos.";
        }
        StringBuilder sb = new StringBuilder("<b>Gastos de hoy</b>\n");
        for (Movimiento m : hoy.gastos()) {
            sb.append("• ").append(formato.linea(m)).append('\n');
        }
        return sb.append("\nTotal: <b>").append(formato.pesos(hoy.total())).append("</b>").toString();
    }

    private String mes() {
        MovimientoService.ResumenMes mes = movimientos.mesActual();
        String titulo = Character.toUpperCase(formato.mes(mes.mes()).charAt(0)) + formato.mes(mes.mes()).substring(1);
        if (mes.porCategoria().isEmpty()) {
            return titulo + ": todavía no hay gastos guardados.";
        }
        return "📅 <b>" + titulo + "</b>\nTotal: <b>" + formato.pesos(mes.total()) + "</b>\n\n"
                + grafico.barras(mes.porCategoria());
    }

    private String costos() {
        ResumenUsoIa r = usoIa.resumenMesActual();
        String mes = formato.mes(YearMonth.now(clock));
        if (r.llamadas() == 0) {
            return "En " + mes + " todavía no se usó la IA.";
        }
        return """
                <b>Uso de la IA en %s</b>
                • Llamadas: %d
                • Tokens: %d de entrada, %d de salida
                • Costo: <b>%s</b>""".formatted(mes, r.llamadas(), r.tokensEntrada(), r.tokensSalida(),
                formato.dolares(r.costoUsd()));
    }
}
