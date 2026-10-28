package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.recibo.PdfInvalidoException;
import ar.gastito.recibo.ReciboService;
import ar.gastito.ticket.ImagenInvalidaException;

/**
 * Recibos de sueldo en el bot: lee el PDF o la foto, arma la tarjeta de confirmación y, al guardar, pregunta si hay
 * que reemplazar el sueldo que ya estaba cargado ese mes.
 */
@Component
public class ConversacionRecibos {

    private static final Logger log = LoggerFactory.getLogger(ConversacionRecibos.class);

    /** El texto que acompaña la foto dice que es un recibo: se lee directo, sin pasar por el ticket. */
    private static final Pattern MENCIONA_RECIBO =
            Pattern.compile("(?i)\\b(recibo|sueldo|aguinaldo|haberes|liquidaci[oó]n)\\b");

    private final ReciboService recibos;
    private final MovimientoService movimientos;
    private final Tarjetas tarjetas;

    public ConversacionRecibos(ReciboService recibos, MovimientoService movimientos, Tarjetas tarjetas) {
        this.recibos = recibos;
        this.movimientos = movimientos;
        this.tarjetas = tarjetas;
    }

    static boolean mencionaRecibo(String texto) {
        return texto != null && MENCIONA_RECIBO.matcher(texto).find();
    }

    // --- Lectura ---

    /** Un PDF: con texto va como texto; escaneado, como imagen. */
    public Accion leerPdf(PdfRecibido pdf) {
        if (pdf.bytes() != null && recibos.superaElTamanio(pdf.bytes())) {
            return Accion.Enviar.texto(demasiadoGrande());
        }
        byte[] bytes;
        try {
            bytes = pdf.descarga().descargar();
        } catch (IOException e) {
            log.warn("No se pudo descargar el PDF de Telegram ({})", e.getClass().getSimpleName());
            return Accion.Enviar.texto("No pude descargar el archivo de Telegram 😕 Probá mandarlo de nuevo.");
        }
        return leer(() -> recibos.leerPdf(bytes, pdf.texto()));
    }

    /** Una foto que el usuario dijo que es un recibo. */
    public Accion leerFoto(FotoRecibida foto) {
        if (foto.bytes() != null && recibos.superaElTamanio(foto.bytes())) {
            return Accion.Enviar.texto(demasiadoGrande());
        }
        byte[] bytes;
        try {
            bytes = foto.descarga().descargar();
        } catch (IOException e) {
            log.warn("No se pudo descargar la foto de Telegram ({})", e.getClass().getSimpleName());
            return Accion.Enviar.texto("No pude descargar la foto de Telegram 😕 Probá mandarla de nuevo.");
        }
        return leerFoto(bytes, foto.texto());
    }

    /** Una foto ya descargada que el ticket reconoció como recibo. */
    public Accion leerFoto(byte[] original, String texto) {
        return leer(() -> recibos.leerImagen(original, texto));
    }

    private Accion leer(Supplier<ReciboService.Resultado> lectura) {
        ReciboService.Resultado resultado;
        try {
            resultado = lectura.get();
        } catch (ImagenInvalidaException e) {
            return Accion.Enviar.texto(switch (e.getMotivo()) {
                case DEMASIADO_GRANDE -> demasiadoGrande();
                case FORMATO_NO_SOPORTADO -> """
                        No pude abrir esa imagen 😕
                        Mandala como foto (no como archivo), o en JPG o PNG.""";
            });
        } catch (PdfInvalidoException e) {
            log.warn("PDF que no se pudo abrir: {}", e.getMessage());
            return Accion.Enviar.texto("""
                    No pude abrir ese PDF 😕
                    Puede estar dañado o tener contraseña. Mandame otro archivo o una foto del recibo.""");
        } catch (IaNoDisponibleException e) {
            return Accion.Enviar.texto("""
                    No pude leer el recibo: %s.
                    Mientras tanto cargá el sueldo a mano, por ejemplo: /sueldo 1.037.500 galicia"""
                    .formatted(html(e.getMessage())));
        }
        return switch (resultado) {
            case ReciboService.Leido l -> Accion.Enviar.tarjeta(tarjetas.pendiente(l.movimiento()),
                    tarjetas.principal(l.movimiento()), l.movimiento().getId());
            case ReciboService.NoEsRecibo n -> Accion.Enviar.texto(switch (n.documento()) {
                case TICKET -> """
                        Eso parece un ticket de compra, no un recibo de sueldo 🤔
                        Mandame la foto del ticket sin la palabra "recibo" o "sueldo".""";
                case RECIBO_SUELDO, OTRO -> """
                        Eso no parece un recibo de sueldo 🤔
                        Mandame el PDF del recibo o una foto clara del recibo impreso.""";
            });
            case ReciboService.Ilegible i -> Accion.Enviar.texto(switch (i.motivo()) {
                case BORROSO -> """
                        No pude leer bien el recibo 📷
                        Mandame el PDF, o una foto con buena luz, con el recibo entero y sin reflejos.""";
                case SIN_NETO -> """
                        No encontré el neto del recibo 🤔 No guardé nada.
                        Mandame el PDF o una foto más clara, con el recibo entero (que se vea el neto a cobrar).""";
                case SIN_PERIODO -> """
                        No pude saber de qué mes es el recibo 🤔 No guardé nada.
                        Mandame el PDF o una foto más clara, donde se vea el período.""";
            });
        };
    }

    private String demasiadoGrande() {
        return """
                El archivo pesa más de %d MB 🙏
                Mandame uno más liviano (una foto, o el PDF del recibo)."""
                .formatted(recibos.tamanioMaximo().toMegabytes());
    }

    // --- Confirmación ---

    /**
     * Al tocar Guardar en un recibo cuyo mes ya tiene sueldo (o aguinaldo) guardado, pregunta antes de dejar dos.
     *
     * @return la pregunta, o vacío si no es un recibo o no hay nada que reemplazar (se guarda normal)
     */
    public Optional<List<Accion>> preguntarSiYaHay(int mensajeId, long id) {
        Movimiento nuevo = movimientos.buscar(id);
        if (nuevo.getOrigen() != Origen.RECIBO || !nuevo.isPendiente()) {
            return Optional.empty();
        }
        return recibos.yaCargado(id).map(anterior -> List.of(
                new Accion.Editar(mensajeId, tarjetas.preguntaReemplazo(nuevo, anterior), tarjetas.reemplazo(id))));
    }

    /** "Sí, reemplazar": guarda el del recibo y deja cancelado el que estaba. */
    public List<Accion> reemplazar(int mensajeId, long id) {
        MovimientoService.Confirmacion c = recibos.confirmarReemplazando(id);
        return List.of(new Accion.Editar(mensajeId, tarjetas.guardado(c.movimiento(), c.totalMesCategoria()),
                Teclado.NINGUNO), new Accion.Avisar("✅ Guardado, reemplacé el anterior"));
    }

    /** Un recibo cancelado no deja nada: ni el detalle ni el archivo. */
    public void alCancelar(Movimiento cancelado) {
        if (cancelado.getOrigen() == Origen.RECIBO) {
            recibos.descartar(cancelado.getId());
        }
    }
}
