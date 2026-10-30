package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;
import static ar.gastito.bot.TextosBot.escuche;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ar.gastito.audio.TranscripcionNoDisponibleException;
import ar.gastito.audio.TranscripcionService;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.movimiento.Origen;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.TicketService;

/** Lo que no es texto: los audios (Whisper y el mismo camino que un texto), las fotos de tickets y los PDF. */
class MediosRecibidos {

    private static final Logger log = LoggerFactory.getLogger(MediosRecibidos.class);

    private final TranscripcionService transcripciones;
    private final TicketService tickets;
    private final ConversacionRecibos recibos;
    private final ConversacionEscalas escalas;
    private final ConversacionAltaFijo altaFijo;
    private final ComandosSueldo comandosSueldo;
    private final EdicionDeCampos edicion;
    private final FlujoDeMensajes flujo;
    private final Duration duracionMaximaAudio;

    MediosRecibidos(TranscripcionService transcripciones, TicketService tickets, ConversacionRecibos recibos,
            ConversacionEscalas escalas, ConversacionAltaFijo altaFijo, ComandosSueldo comandosSueldo,
            EdicionDeCampos edicion, FlujoDeMensajes flujo, Duration duracionMaximaAudio) {
        this.transcripciones = transcripciones;
        this.tickets = tickets;
        this.recibos = recibos;
        this.escalas = escalas;
        this.altaFijo = altaFijo;
        this.comandosSueldo = comandosSueldo;
        this.edicion = edicion;
        this.flujo = flujo;
        this.duracionMaximaAudio = duracionMaximaAudio;
    }

    // --- Audios ---

    /**
     * Se transcribe y sigue el mismo camino que un texto. Si la tarjeta espera el valor de un campo y lo que se
     * escuchó sirve para ese campo, se propone (con Aplicar y Cancelar edición) en vez de aplicarlo directo.
     * Fuera de la edición, un audio siempre es un gasto nuevo.
     *
     * @param mensajeId el audio del usuario (se borra si era el valor de un campo)
     */
    List<Accion> audio(long chatId, int mensajeId, AudioRecibido audio) {
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
        EdicionDeCampos.Espera espera = edicion.quitar(chatId);
        if (espera != null) {
            Optional<List<Accion>> propuesta = edicion.proponer(chatId, espera, escuchado, mensajeId);
            if (propuesta.isPresent()) {
                return propuesta.get();
            }
            acciones.addAll(edicion.restaurar(espera));
        }
        if (comandosSueldo.pareceCalculo(escuchado)) {
            acciones.add(comandosSueldo.calcularDeTexto(escuchado));
        } else {
            acciones.addAll(flujo.nuevoGasto(chatId, escuchado, Origen.AUDIO));
        }
        return acciones;
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

    // --- Fotos de tickets, recibos y escalas ---

    /**
     * Se lee el ticket y queda la tarjeta de siempre, con los productos. Una foto no es el valor de un campo: si
     * la tarjeta esperaba uno, vuelve a su estado normal. Si el texto dice que es la escala o un recibo, se lee
     * directo como eso ("escala de sueldo" es la escala, aunque diga sueldo).
     */
    List<Accion> foto(long chatId, FotoRecibida foto) {
        List<Accion> acciones = restaurarEspera(chatId);
        if (ConversacionEscalas.mencionaEscala(foto.texto())) {
            acciones.add(foto.bytes() != null && tickets.superaElTamanio(foto.bytes())
                    ? Accion.Enviar.texto(demasiadoGrande()) : escalas.leerFoto(foto));
        } else {
            acciones.add(ConversacionRecibos.mencionaRecibo(foto.texto()) ? recibos.leerFoto(foto)
                    : nuevoTicket(foto));
        }
        return acciones;
    }

    /** Un Excel es la escala de sueldo (la hoja del sindicato o la plantilla). */
    List<Accion> excel(long chatId, ExcelRecibido excel) {
        List<Accion> acciones = restaurarEspera(chatId);
        acciones.add(escalas.leerExcel(excel));
        return acciones;
    }

    /** Un PDF es un recibo de sueldo: queda la tarjeta con el neto y el detalle, para guardar. */
    List<Accion> pdf(long chatId, PdfRecibido pdf) {
        List<Accion> acciones = restaurarEspera(chatId);
        acciones.add(recibos.leerPdf(pdf));
        return acciones;
    }

    private List<Accion> restaurarEspera(long chatId) {
        List<Accion> acciones = new ArrayList<>();
        EdicionDeCampos.Espera espera = edicion.quitar(chatId);
        if (espera != null) {
            acciones.addAll(edicion.restaurar(espera));
        }
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
            case TicketService.Leido l -> flujo.tarjetaNueva(l.movimiento());
            case TicketService.EsRecibo r -> recibos.leerFoto(bytes, foto.texto());
            case TicketService.EsEscala e -> escalas.leerFoto(bytes, foto.texto());
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
}
