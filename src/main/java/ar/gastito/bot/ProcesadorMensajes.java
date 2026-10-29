package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
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
import ar.gastito.fijo.GastoFijo;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.interpretacion.DivisorDeGastos;
import ar.gastito.interpretacion.Intencion;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ParserHorasSueldo;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.interpretacion.ValidadorInterpretacion;
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
            Contame lo que quieras como si me escribieras por chat, o mandame un audio 🎙:

            • kiosco 1500
            • pagué 12000 de nafta con la tarjeta
            • cobré el sueldo, 980 mil en Galicia
            • agregá un fijo: el seguro de la moto, unos 32 mil por mes
            • ¿cuánto gasté en el súper este mes?

            También podés mandarme la foto del ticket 🧾. Te muestro lo que entendí y lo guardás con un toque.""";

    static final String AYUDA = """
            Decime las cosas como te salgan, por escrito o en un audio 🎙. Si me falta un dato te lo pregunto con botones, y si no estoy seguro de qué querés, te doy opciones.

            <b>🧾 Gastos</b>
            • "kiosco 1500", "ayer 3700 en pan y papas con QR"
            • "pagué 48000 el asado, éramos 6": en tus gastos cuenta solo tu parte
            • "zapatillas 84 lucas en 6 cuotas con la tarjeta"
            • La foto del ticket: leo el comercio, los productos y el total

            <b>💰 Ingresos</b>
            • "cobré el sueldo, 980 mil en Galicia", "cobré el aguinaldo"
            • "me pagaron 25 mil por arreglar una PC"
            • "Pablo me pasó 8000 del asado" (te devuelven plata de una juntada)

            <b>📌 Gastos fijos</b>
            • "agregá un fijo: el seguro de la moto, unos 32 mil por mes con la tarjeta"
            • "de luz pagué 87.500", "ya pagué el seguro"
            • "¿qué fijos me faltan pagar?", "sacá Netflix de los fijos"

            <b>✏️ Corregir</b>
            • "el último era 3500, no 3700", "lo de recién fue con débito"
            • "borrá lo último que cargué"
            • "cancelá las cuotas de las zapatillas", "lo de Pablo dalo por perdido"

            <b>🧮 Sueldo</b>
            • "este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas": calculo el sueldo del mes
            • "¿cuánto me da el sueldo calculado?"
            • El PDF o la foto del recibo: lo comparo con tu cálculo y con lo que cobraste

            <b>❓ Preguntas y listas</b>
            • "¿cuánto gasté en kiosco este mes?", "compará octubre con septiembre"
            • "¿quién me debe plata?", "¿qué cuotas tengo?", "¿cuánto llevo de IA este mes?"
            • "mandame el resumen de la semana" (o del mes), "pasame el link del dashboard"
            Los números los calculo con tu base, no los inventa la IA.

            <b>📬 Lo que te mando solo</b>
            El domingo a las 21 te mando cómo fue tu semana y el día 1 a las 9, cómo fue el mes. Si la PC estaba apagada, te llega apenas la prendés.

            <b>Atajos</b> (si preferís comandos)
            /hoy · /mes · /disponible · /resumen · /juntadas · /fijos · /cuotas · /calculo · /costos · /dashboard""";

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
    private final ConversacionAltaFijo altaFijo;
    private final PedidosNaturales naturales;
    private final ComandoDisponible disponible;
    private final GastoFijoService gastosFijos;
    private final ConversacionVarios varios;
    private final ConversacionCuotas cuotas;
    private final ConversacionRecibos recibos;
    private final ComandosSueldo comandosSueldo;
    private final FormatoCostos formatoCostos;
    private final FormatoResumen formatoResumen;
    private final ConsultaService consultas;
    private final FormatoConsulta formatoConsulta;

    /**
     * Botones de un mensaje dudoso: "dg:<id>" (es un gasto), "dp:<id>" (es una pregunta) y "do:<id>:<intención>"
     * (cualquier otra de las opciones, por su ordinal).
     */
    static final String ES_GASTO = "dg";
    static final String ES_PREGUNTA = "dp";
    static final String ES_OTRA = "do";
    /** "Fue ≈ $32.000": el pago de un fijo dicho sin monto toma el aproximado del fijo. */
    static final String PAGO_APROXIMADO = "fy";
    /** Cuántos mensajes dudosos se recuerdan esperando un botón (los más viejos se olvidan). */
    static final int DUDOSOS_RECORDADOS = 20;

    /** Un mensaje que no está claro qué pide, esperando que el usuario elija entre las opciones. */
    private record Dudoso(InterpretadorService.Dudoso dudoso, String texto, Origen origen) {
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
            ConversacionAltaFijo altaFijo, PedidosNaturales naturales, ComandoDisponible disponible,
            GastoFijoService gastosFijos,
            ConversacionVarios varios, ConversacionCuotas cuotas, ConversacionRecibos recibos,
            ComandosSueldo comandosSueldo, FormatoCostos formatoCostos, GastitoProperties properties,
            DashboardProperties dashboard, Clock clock) {
        this.fijos = fijos;
        this.altaFijo = altaFijo;
        this.naturales = naturales;
        this.disponible = disponible;
        this.gastosFijos = gastosFijos;
        this.varios = varios;
        this.cuotas = cuotas;
        this.recibos = recibos;
        this.comandosSueldo = comandosSueldo;
        this.formatoCostos = formatoCostos;
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

        // El alta de un fijo esperaba un dato (el monto, el día, el nombre). Si no sirve, se procesa normal.
        if (altaFijo.esperando(chatId)) {
            if (comando == null) {
                Optional<Accion.Enviar> respuesta = altaFijo.texto(chatId, limpio);
                if (respuesta.isPresent()) {
                    return List.of(respuesta.get());
                }
            }
            altaFijo.olvidar(chatId);
        }

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

        if (comando != null) {
            acciones.addAll(ejecutarComando(chatId, comando, limpio));
        } else if (comandosSueldo.pareceCalculo(limpio)) {
            acciones.add(comandosSueldo.calcularDeTexto(limpio));
        } else {
            acciones.addAll(nuevoGasto(chatId, limpio, Origen.TEXTO));
        }
        return acciones;
    }

    /**
     * Un comando (los atajos) o lo mismo pedido en lenguaje natural ("¿qué fijos me faltan?" → /fijos). Si los
     * argumentos de un atajo no se entienden sin IA, se interpretan como cualquier mensaje: nunca se le pide al
     * usuario que respete un formato.
     *
     * @param limpio el mensaje entero, con el comando ("/fijo seguro 32900 tarjeta día 4")
     */
    private List<Accion> ejecutarComando(long chatId, String comando, String limpio) {
        String argumentos = limpio.replaceFirst("^/\\S+\\s*", "");
        return switch (comando) {
            case "/resumen" -> List.of(resumen(limpio));
            case "/juntadas" -> juntadas.abiertas();
            case "/fijo" -> argumentos.isBlank() ? List.of(Accion.Enviar.texto(ConversacionAltaFijo.preguntaInicial()))
                    : altaFijo.desdeComando(chatId, argumentos).<List<Accion>>map(List::of)
                            .orElseGet(() -> nuevoGasto(chatId, "agregá un gasto fijo: " + argumentos, Origen.TEXTO));
            case "/fijos" -> fijos.lista();
            case "/cuotas" -> cuotas.lista();
            case "/calcular" -> argumentos.isBlank() || ParserHorasSueldo.posicionales(argumentos).isPresent()
                    ? List.of(comandosSueldo.calcularDeComando(limpio))
                    : nuevoGasto(chatId, "este mes hice " + argumentos, Origen.TEXTO);
            case "/calculo" -> List.of(comandosSueldo.verCalculo());
            default -> {
                ClaseIngreso clase = ComandosIngreso.clase(comando);
                if (clase == null) {
                    yield List.of(responderComando(comando));
                }
                yield comandosIngreso.responder(clase, limpio).<List<Accion>>map(List::of)
                        .orElseGet(() -> nuevoGasto(chatId, ComandosIngreso.comoFrase(clase) + argumentos,
                                Origen.TEXTO));
            }
        };
    }

    /** El comando que muestra lo mismo que una lista pedida en lenguaje natural. */
    static String comandoDe(ValidadorInterpretacion.Lista lista) {
        return switch (lista) {
            case FIJOS -> "/fijos";
            case DEUDAS -> "/juntadas";
            case CUOTAS -> "/cuotas";
            case COSTOS_IA -> "/costos";
            case RESUMEN_SEMANA -> "/resumen";
            case RESUMEN_MES -> "/resumen mes";
            case HOY -> "/hoy";
            case MES -> "/mes";
            case CALCULO -> "/calculo";
            case DASHBOARD -> "/dashboard";
            case AYUDA -> "/ayuda";
        };
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

        // El alta de un fijo esperaba un dato ("unos 32 mil"): si sirve, se usa; si no, se procesa normal.
        if (altaFijo.esperando(chatId)) {
            Optional<Accion.Enviar> respuesta = altaFijo.texto(chatId, escuchado);
            if (respuesta.isPresent()) {
                return List.of(respuesta.get().conPrefijo(escuche(escuchado)));
            }
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
        if (comandosSueldo.pareceCalculo(escuchado)) {
            acciones.add(comandosSueldo.calcularDeTexto(escuchado));
        } else {
            acciones.addAll(nuevoGasto(chatId, escuchado, Origen.AUDIO));
        }
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
        acciones.add(ConversacionRecibos.mencionaRecibo(foto.texto()) ? recibos.leerFoto(foto) : nuevoTicket(foto));
        return acciones;
    }

    // --- Recibos de sueldo ---

    /** Un PDF es un recibo de sueldo: queda la tarjeta con el neto y el detalle, para guardar. */
    public List<Accion> procesarPdf(long chatId, int mensajeId, PdfRecibido pdf) {
        List<Accion> acciones = new ArrayList<>();
        Espera espera = esperas.remove(chatId);
        if (espera != null) {
            acciones.addAll(restaurar(espera));
        }
        acciones.add(recibos.leerPdf(pdf));
        return acciones;
    }

    private Accion nuevoTicket(FotoRecibida foto) {
        if (foto.bytes() != null && tickets.superaElTamanio(foto.bytes())) {
            return Accion.Enviar.texto(demasiadoGrande());
        }
        TicketService.Resultado resultado;
        byte[] bytes;
        try {
            bytes = foto.descarga().descargar();
            resultado = tickets.leer(bytes, foto.texto());
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
            case TicketService.EsRecibo r -> recibos.leerFoto(bytes, foto.texto());
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

    /** Lo que diga el mensaje (un gasto, un ingreso, un fijo, una pregunta, una corrección…), interpretado. */
    private List<Accion> nuevoGasto(long chatId, String texto, Origen origen) {
        boolean audio = origen == Origen.AUDIO;
        List<String> partes = DivisorDeGastos.separar(texto);
        if (partes.size() > 1) {
            Optional<Accion> varios = intentarVarios(partes, origen, texto, audio);
            if (varios.isPresent()) {
                return List.of(varios.get());
            }
        }
        InterpretadorService.Resultado resultado;
        try {
            resultado = interpretador.interpretar(texto, audio ? PropositoIa.AUDIO : PropositoIa.TEXTO);
        } catch (IaNoDisponibleException e) {
            return List.of(Accion.Enviar.texto((audio ? escuche(texto) : "") + """
                    No pude usar la IA para entender el mensaje: %s.
                    Lo simple lo entiendo igual (kiosco 1500); lo demás, probá de nuevo en un rato."""
                    .formatted(html(e.getMessage()))));
        }
        return responder(chatId, resultado, texto, origen);
    }

    private List<Accion> responder(long chatId, InterpretadorService.Resultado resultado, String texto,
            Origen origen) {
        boolean audio = origen == Origen.AUDIO;
        String prefijo = audio ? escuche(texto) : "";
        return switch (resultado) {
            case InterpretadorService.Interpretado i -> List.of(gastoInterpretado(chatId, i, texto, origen));
            case InterpretadorService.Pregunta _ -> List.of(Accion.Enviar.conMenu(responderPregunta(texto, audio)));
            case InterpretadorService.Dudoso d -> List.of(preguntarQueQuiere(d, texto, origen, prefijo));
            case InterpretadorService.Devolucion d -> List.of(juntadas.devolucion(d.datos(), origen, texto, prefijo));
            case InterpretadorService.AltaFijo a -> List.of(altaFijo.nueva(chatId, a.pedido(), prefijo));
            case InterpretadorService.Orden o -> orden(chatId, o.orden(), texto, origen, prefijo);
            case InterpretadorService.NoEsGasto _ -> List.of(Accion.Enviar.texto(prefijo + """
                    🤔 No encontré nada para hacer en %s.
                    Contame un gasto o un ingreso, preguntame algo o pedime una lista, como te salga. En /ayuda tenés ejemplos."""
                    .formatted(audio ? "lo que escuché" : "el mensaje")));
        };
    }

    private Accion gastoInterpretado(long chatId, InterpretadorService.Interpretado i, String texto, Origen origen) {
        Movimiento m = crearGasto(i, origen, texto);
        Optional<GastoFijo> fijo = m.isPendiente() ? gastosFijos.vincular(texto) : Optional.empty();
        fijo.ifPresent(f -> gastosFijos.vincular(m, f));
        if (m.getMonto() == null) {
            esperas.put(chatId, new Espera(m.getId(), Campo.MONTO, null));
            // "Ya pagué el seguro": si el fijo tiene un monto de referencia, un botón lo usa.
            Teclado teclado = fijo.flatMap(f -> fijos.aproximado(f.getId()))
                    .map(a -> conFila(tarjetas.principal(m), new Teclado.Boton("✅ Fue ≈ " + formato.pesos(a),
                            PAGO_APROXIMADO + ":" + m.getId())))
                    .orElseGet(() -> tarjetas.principal(m));
            return Accion.Enviar.tarjeta(tarjetas.pidiendoMontoFaltante(m), teclado, m.getId());
        }
        return tarjetaNueva(m);
    }

    private static Teclado conFila(Teclado teclado, Teclado.Boton boton) {
        List<List<Teclado.Boton>> filas = new ArrayList<>();
        filas.add(List.of(boton));
        filas.addAll(teclado.filas());
        return new Teclado(filas);
    }

    /** No está claro qué quiere: un botón por opción (2 o 3), la más probable primero. */
    private Accion preguntarQueQuiere(InterpretadorService.Dudoso d, String texto, Origen origen, String prefijo) {
        long id = siguienteDudoso.incrementAndGet();
        dudosos.put(id, new Dudoso(d, texto, origen));
        boolean gastoOPregunta = d.opciones().equals(List.of(Intencion.GASTO, Intencion.PREGUNTA));
        List<Teclado.Boton> botones = d.opciones().stream().map(o -> botonDeOpcion(o, id)).toList();
        return Accion.Enviar.conBotones(prefijo + (gastoOPregunta
                ? "🤔 ¿Es un gasto para cargar o una pregunta sobre tus gastos?" : "🤔 ¿Qué querés hacer?"),
                Teclado.constructor().grilla(botones, botones.size() <= 2 ? 2 : 1).build());
    }

    static Teclado.Boton botonDeOpcion(Intencion opcion, long id) {
        return switch (opcion) {
            case GASTO -> new Teclado.Boton("🧾 Es un gasto", ES_GASTO + ":" + id);
            case PREGUNTA -> new Teclado.Boton("❓ Es una pregunta", ES_PREGUNTA + ":" + id);
            default -> new Teclado.Boton(switch (opcion) {
                case INGRESO -> "💰 Es un ingreso";
                case DEVOLUCION -> "🤝 Me devolvieron plata";
                case ALTA_FIJO -> "📌 Agregar un gasto fijo";
                case BAJA_FIJO -> "🗑 Dar de baja un fijo";
                case CORREGIR -> "✏️ Corregir lo último";
                case BORRAR -> "🗑 Borrar lo último";
                case CALCULO_SUELDO -> "🧮 Calcular el sueldo";
                case CANCELAR_CUOTAS -> "🗂 Cancelar unas cuotas";
                case PERDONAR -> "🚫 Dar por perdida una deuda";
                default -> "📋 Ver una lista";
            }, ES_OTRA + ":" + id + ":" + opcion.ordinal());
        };
    }

    /** Los demás pedidos: un ingreso, corregir o borrar lo último, una lista, el sueldo, cuotas, deudas, fijos. */
    private List<Accion> orden(long chatId, ValidadorInterpretacion.Resultado orden, String texto, Origen origen,
            String prefijo) {
        return switch (orden) {
            case ValidadorInterpretacion.Ver v -> ejecutarComando(chatId, comandoDe(v.lista()).split(" ")[0],
                    comandoDe(v.lista()));
            case ValidadorInterpretacion.Ingreso i -> {
                Movimiento m = comandosIngreso.crear(i, origen, texto);
                if (m.getMonto() == null) {
                    esperas.put(chatId, new Espera(m.getId(), Campo.MONTO, null));
                    yield List.of(Accion.Enviar.tarjeta(prefijo + tarjetas.pidiendoMontoFaltante(m),
                            tarjetas.principal(m), m.getId()));
                }
                yield List.of(Accion.Enviar.tarjeta(prefijo + tarjetas.pendiente(m), tarjetas.principal(m),
                        m.getId()));
            }
            case ValidadorInterpretacion.Correccion c -> List.of(naturales.corregir(c, prefijo));
            case ValidadorInterpretacion.Borrar _ -> List.of(naturales.borrar(prefijo));
            case ValidadorInterpretacion.CalculoSueldo c -> List.of(comandosSueldo.calcularDeHoras(c.horas(), prefijo));
            case ValidadorInterpretacion.CancelarCuotas c -> naturales.cancelarCuotas(c.cual(), prefijo)
                    .<List<Accion>>map(List::of).orElseGet(cuotas::lista);
            case ValidadorInterpretacion.Perdonar p -> naturales.perdonar(p.persona(), p.de(), prefijo)
                    .<List<Accion>>map(List::of).orElseGet(juntadas::abiertas);
            case ValidadorInterpretacion.BajaFijo b -> naturales.bajaDeFijo(b.cual(), prefijo)
                    .<List<Accion>>map(List::of).orElseGet(fijos::lista);
            default -> List.of(Accion.Enviar.texto(prefijo + "🤔 No entendí qué querés hacer. ¿Me lo contás de otra forma?"));
        };
    }

    /**
     * Varios gastos separados por {@link DivisorDeGastos}: se interpreta cada parte por separado (sin guardar
     * nada) y, solo si todas resultan un gasto con monto, se crean los pendientes y se vinculan a su fijo si
     * corresponde. Si alguna parte no es un gasto claro, se cae al camino de un solo gasto con el texto completo.
     */
    private Optional<Accion> intentarVarios(List<String> partes, Origen origen, String textoCompleto,
            boolean audio) {
        List<InterpretadorService.Interpretado> interpretados = new ArrayList<>();
        for (String parte : partes) {
            InterpretadorService.Resultado r;
            try {
                r = interpretador.interpretar(parte, audio ? PropositoIa.AUDIO : PropositoIa.TEXTO);
            } catch (IaNoDisponibleException e) {
                return Optional.empty();
            }
            if (!(r instanceof InterpretadorService.Interpretado i) || i.movimiento().monto() == null) {
                return Optional.empty();
            }
            interpretados.add(i);
        }
        List<Movimiento> creados = new ArrayList<>();
        for (int idx = 0; idx < partes.size(); idx++) {
            Movimiento m = crearGasto(interpretados.get(idx), origen, partes.get(idx));
            Optional<GastoFijo> fijo = gastosFijos.vincular(partes.get(idx));
            if (fijo.isPresent() && m.isPendiente()) {
                gastosFijos.vincular(m, fijo.get());
            }
            creados.add(m);
        }
        return Optional.of(varios.tarjeta(creados, audio ? escuche(textoCompleto) : ""));
    }

    private Movimiento crearGasto(InterpretadorService.Interpretado i, Origen origen, String texto) {
        Movimiento m = movimientos.crearPendiente(i.movimiento(), origen, texto);
        if (i.usoIaId() != null) {
            usoIa.asociarMovimiento(i.usoIaId(), m.getId());
        }
        // Una juntada sin monto queda como gasto común: sin el total no hay nada que repartir.
        if (i.juntada() != null && m.getMonto() != null) {
            return juntadas.crear(m, i.juntada());
        }
        if (i.cuotas() != null && m.getMonto() != null) {
            return cuotas.crear(m, i.cuotas());
        }
        return m;
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
        Movimiento m = crearGasto(dudoso.dudoso().comoGasto(), dudoso.origen(), dudoso.texto());
        return List.of(m.getMonto() == null ? principal(chatId, mensajeId, m)
                : repetidos.siEsRepetido(m, mensajeId).orElseGet(() -> principal(chatId, mensajeId, m)));
    }

    /** La tarjeta de un gasto recién creado con monto: la de siempre, o la de "¿ya lo tenías registrado?". */
    private Accion.Enviar tarjetaNueva(Movimiento m) {
        return fijos.siActualizaUnEstimado(m)
                .or(() -> repetidos.siEsRepetido(m))
                .orElseGet(() -> Accion.Enviar.tarjeta(tarjetas.pendiente(m)
                        + (m.getGastoFijoId() == null ? "" : fijos.avisoYaCargado(m)), tarjetas.principal(m),
                        m.getId()));
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


    /**
     * Eligió otra de las opciones (un ingreso, un fijo, corregir lo último…): se valida la misma respuesta de la IA
     * con esa intención, sin volver a llamarla, y el mensaje de la pregunta queda con lo que eligió.
     */
    private List<Accion> esOtra(long chatId, int mensajeId, long id, long ordinal) {
        Dudoso dudoso = dudosos.remove(id);
        if (dudoso == null || ordinal < 0 || ordinal >= Intencion.values().length) {
            return List.of(new Accion.Avisar("Ese mensaje ya no está pendiente"));
        }
        Intencion elegida = Intencion.values()[(int) ordinal];
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.Editar(mensajeId, "👉 " + html(botonDeOpcion(elegida, id).texto()),
                Teclado.NINGUNO));
        acciones.addAll(responder(chatId, interpretador.reinterpretar(dudoso.dudoso(), elegida, dudoso.texto()),
                dudoso.texto(), dudoso.origen()));
        return acciones;
    }

    /** "✅ Fue ≈ $32.000": el pago de un fijo dicho sin monto toma el monto de referencia del fijo. */
    private List<Accion> pagoAproximado(long chatId, int mensajeId, long movimientoId) {
        Movimiento m = pendiente(movimientoId);
        Optional<java.math.BigDecimal> aproximado = m.getGastoFijoId() == null ? Optional.empty()
                : fijos.aproximado(m.getGastoFijoId());
        if (aproximado.isEmpty()) {
            return List.of(principal(chatId, mensajeId, m), new Accion.Avisar("Ese fijo no tiene monto de referencia"));
        }
        return List.of(principal(chatId, mensajeId, movimientos.cambiarMonto(movimientoId, aproximado.get())));
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
        if (ComandosSueldo.esBoton(datos)) {
            return comandosSueldo.boton(mensajeId, datos);
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
        altaFijo.olvidar(chatId);
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
                if (c.movimiento().getPlanCuotasId() != null) {
                    yield cuotas.guardada(mensajeId, c);
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
            case Tarjetas.EDITAR -> List.of(new Accion.Editar(mensajeId, tarjetas.pendiente(pendiente(id)),
                    tarjetas.edicion(id)));
            case TarjetasRecibo.DESCARTAR -> recibos.descartar(mensajeId, id);
            case Tarjetas.PERIODO -> List.of(principal(chatId, mensajeId, comandosIngreso.alternarPeriodo(id)));
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
            case ConversacionFijos.BAJA, ConversacionFijos.ESTE_MES_NO_CHECKLIST -> fijos.boton(mensajeId, accion, id);
            case ConversacionAltaFijo.GUARDAR, ConversacionAltaFijo.CANCELAR, ConversacionAltaFijo.CATEGORIA,
                    ConversacionAltaFijo.DIA, ConversacionAltaFijo.MEDIO, ConversacionAltaFijo.MONTO_NO_SE,
                    ConversacionAltaFijo.EDITAR, ConversacionAltaFijo.CAMBIAR, ConversacionAltaFijo.FRECUENCIA,
                    ConversacionAltaFijo.VOLVER, ConversacionAltaFijo.ACTUALIZAR_EXISTENTE, ConversacionAltaFijo.ES_OTRO ->
                    altaFijo.boton(chatId, mensajeId, accion, id, elegido);
            case PedidosNaturales.APLICAR_CORRECCION, PedidosNaturales.BORRAR, PedidosNaturales.BAJA_FIJO,
                    PedidosNaturales.NADA -> naturales.boton(mensajeId, accion, id);
            case PAGO_APROXIMADO -> pagoAproximado(chatId, mensajeId, id);
            case ES_OTRA -> esOtra(chatId, mensajeId, id, requerido(elegido));
            case ConversacionFijos.CONFIRMAR_COBRO, ConversacionFijos.CAMBIO_EL_MONTO, ConversacionFijos.ESTE_MES_NO ->
                    fijos.botonDeCobro(chatId, mensajeId, accion, id);
            case ConversacionFijos.PAGADO, ConversacionFijos.ACTUALIZAR_ESTIMADO ->
                    fijos.botonDePago(mensajeId, accion, id);
            case ConversacionFijos.OTRO_PAGO -> List.of(principal(chatId, mensajeId, pendiente(id)));
            case ConversacionCuotas.CANCELAR_PLAN -> cuotas.boton(mensajeId, id);
            case ConversacionVarios.GUARDAR_TODO, ConversacionVarios.CANCELAR_TODOS -> varios.boton(mensajeId,
                    accion, id);
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
            case "/disponible" -> disponible.responder();
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
        return formatoCostos.comando();
    }
}
