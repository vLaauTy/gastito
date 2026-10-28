package ar.gastito.recibo;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Periodo;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.ImagenTicket;

/**
 * Del recibo al ingreso pendiente: lo lee con la IA (el texto del PDF, o la imagen si es un escaneo o una foto), lo
 * valida en Java y guarda el ingreso por el neto con el detalle de conceptos y el archivo en disco. Después sigue
 * el flujo de confirmación de siempre (Guardar / Editar / Cancelar).
 */
@Service
public class ReciboService {

    private static final Logger log = LoggerFactory.getLogger(ReciboService.class);
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final int LARGO_MAXIMO_DESCRIPCION = 255;

    private final LectorPdf pdfs;
    private final ImagenTicket imagenes;
    private final LectorRecibo lector;
    private final ValidadorRecibo validador;
    private final IngresoService ingresos;
    private final MovimientoService movimientos;
    private final ReciboRepository recibos;
    private final RegistroUsoIa usoIa;
    private final ArchivosRecibo archivos;
    private final DataSize tamanioMaximo;
    private final Clock clock;

    public ReciboService(LectorPdf pdfs, ImagenTicket imagenes, LectorRecibo lector, ValidadorRecibo validador,
            IngresoService ingresos, MovimientoService movimientos, ReciboRepository recibos, RegistroUsoIa usoIa,
            ArchivosRecibo archivos, GastitoProperties properties, Clock clock) {
        this.pdfs = pdfs;
        this.imagenes = imagenes;
        this.lector = lector;
        this.validador = validador;
        this.ingresos = ingresos;
        this.movimientos = movimientos;
        this.recibos = recibos;
        this.usoIa = usoIa;
        this.archivos = archivos;
        this.tamanioMaximo = properties.ticket().tamanioMaximo();
        this.clock = clock;
    }

    public sealed interface Resultado {
    }

    /** El ingreso pendiente, con el detalle del recibo. */
    public record Leido(Movimiento movimiento) implements Resultado {
    }

    public record NoEsRecibo(TipoDocumento documento) implements Resultado {
    }

    public record Ilegible(ValidadorRecibo.Motivo motivo) implements Resultado {
    }

    /** Un renglón de la tarjeta o del dashboard. */
    public record Renglon(String descripcion, GrupoConcepto grupo, BigDecimal importe) {
    }

    public record VistaRecibo(Long id, Long movimientoId, String empleador, YearMonth periodo, TipoRecibo tipo,
            LocalDate fechaPago, BigDecimal bruto, BigDecimal descuentos, BigDecimal neto, List<Renglon> conceptos,
            String aviso, boolean conArchivo) {
    }

    public DataSize tamanioMaximo() {
        return tamanioMaximo;
    }

    public boolean superaElTamanio(long bytes) {
        return bytes > tamanioMaximo.toBytes();
    }

    // --- Lectura ---

    /**
     * @param pista lo que escribió el usuario con el archivo, o null
     * @throws ImagenInvalidaException si pesa demasiado
     * @throws PdfInvalidoException si no se puede abrir
     * @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA
     */
    public Resultado leerPdf(byte[] pdf, String pista) {
        controlarTamanio(pdf);
        LocalDateTime ahora = ahora();
        Optional<String> texto = pdfs.texto(pdf);
        if (texto.isPresent()) {
            ClienteIa.Respuesta<LecturaReciboIa> respuesta = lector.leerTexto(texto.get(), pista, ahora);
            return procesar(respuesta, pdf, "pdf");
        }
        // Sin texto: es un escaneo, se lee como una foto.
        ImagenTicket.Imagen imagen = imagenes.preparar(pdfs.primeraPagina(pdf));
        ClienteIa.Respuesta<LecturaReciboIa> respuesta = lector.leerImagen(imagen.jpeg(), pista, ahora);
        return procesar(respuesta, pdf, "pdf");
    }

    /**
     * @throws ImagenInvalidaException si pesa demasiado o no se puede abrir
     * @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA
     */
    public Resultado leerImagen(byte[] original, String pista) {
        controlarTamanio(original);
        ImagenTicket.Imagen imagen = imagenes.preparar(original);
        ClienteIa.Respuesta<LecturaReciboIa> respuesta = lector.leerImagen(imagen.jpeg(), pista, ahora());
        return procesar(respuesta, imagen.jpeg(), "jpg");
    }

    private void controlarTamanio(byte[] contenido) {
        if (superaElTamanio(contenido.length)) {
            throw new ImagenInvalidaException(ImagenInvalidaException.Motivo.DEMASIADO_GRANDE,
                    "El archivo pesa " + contenido.length + " bytes");
        }
    }

    private Resultado procesar(ClienteIa.Respuesta<LecturaReciboIa> respuesta, byte[] archivo, String extension) {
        return switch (validador.validar(respuesta.valor())) {
            case ValidadorRecibo.Valido v -> new Leido(guardar(v.recibo(), respuesta.usoIaId(), archivo, extension));
            case ValidadorRecibo.NoEsRecibo n -> new NoEsRecibo(n.documento());
            case ValidadorRecibo.Ilegible i -> new Ilegible(i.motivo());
        };
    }

    /**
     * Guarda el ingreso pendiente de un recibo ya validado, con su detalle y el archivo (si hay).
     *
     * @param usoIaId la llamada a la IA que lo leyó, o null
     * @param archivo el PDF o la imagen, o null si no hay archivo (datos de prueba)
     */
    public Movimiento guardar(ReciboLeido leido, Long usoIaId, byte[] archivo, String extension) {
        Movimiento m = ingresos.crearPendienteDeRecibo(leido.tipo().claseIngreso(), leido.neto(),
                descripcion(leido), fechaDelIngreso(leido));
        ReciboSueldo recibo = recibos.save(new ReciboSueldo(m.getId(), leido));
        if (usoIaId != null) {
            usoIa.asociarMovimiento(usoIaId, m.getId());
        }
        if (archivo == null) {
            return m;
        }
        try {
            recibo.asociarArchivo(archivos.guardar(m.getId(), leido.periodo(), extension, archivo));
            recibos.save(recibo);
        } catch (IOException e) {
            // Sin el archivo el ingreso sirve igual: solo no se va a poder abrir desde el dashboard.
            log.warn("No se pudo guardar el archivo del recibo {}: {}", m.getId(), e.getMessage());
        }
        return m;
    }

    /** "Sueldo octubre 2026 · Acme". */
    static String descripcion(ReciboLeido leido) {
        String mes = leido.periodo().getMonth().getDisplayName(java.time.format.TextStyle.FULL, ES_AR);
        String texto = leido.tipo().getNombre() + " " + mes + " " + leido.periodo().getYear() + " · "
                + leido.empleador();
        return texto.length() > LARGO_MAXIMO_DESCRIPCION
                ? texto.substring(0, LARGO_MAXIMO_DESCRIPCION) : texto;
    }

    /**
     * El ingreso va en el mes del período. Si la fecha de pago cae en ese mes, es ese día; si no (el sueldo de
     * octubre se cobra el 3 de noviembre), el último día del período, o hoy si todavía no llegó.
     */
    LocalDateTime fechaDelIngreso(ReciboLeido leido) {
        LocalDate hoy = LocalDate.now(clock);
        LocalDate dia;
        if (leido.fechaPago() != null && YearMonth.from(leido.fechaPago()).equals(leido.periodo())) {
            dia = leido.fechaPago();
        } else {
            dia = leido.periodo().atEndOfMonth();
        }
        if (dia.isAfter(hoy)) {
            dia = hoy.isBefore(leido.periodo().atDay(1)) ? leido.periodo().atDay(1) : hoy;
        }
        return dia.atStartOfDay().plusHours(12);
    }

    // --- Confirmación ---

    /**
     * El sueldo (o aguinaldo) ya guardado del mes del período, si lo hay: hay que preguntar antes de dejar dos.
     * Solo para sueldos mensuales y aguinaldos.
     */
    @Transactional(readOnly = true)
    public Optional<Movimiento> yaCargado(Long movimientoId) {
        Optional<ReciboSueldo> recibo = recibos.findByMovimientoId(movimientoId);
        if (recibo.isEmpty() || !recibo.get().getTipo().reemplazaCargaManual()) {
            return Optional.empty();
        }
        Periodo mes = Periodo.delMes(recibo.get().getPeriodo());
        return recibos.ingresosGuardados(recibo.get().getTipo().claseIngreso(), mes.desde(), mes.hasta(),
                movimientoId).stream().findFirst();
    }

    /** Guarda el ingreso del recibo y borra (queda cancelado) el del mismo mes que ya estaba. */
    @Transactional
    public MovimientoService.Confirmacion confirmarReemplazando(Long movimientoId) {
        Optional<Movimiento> anterior = yaCargado(movimientoId);
        // Primero se anula el anterior: el total del mes que se muestra al guardar ya no lo cuenta.
        anterior.ifPresent(m -> ingresos.anular(m.getId()));
        return movimientos.confirmar(movimientoId);
    }

    /** Un recibo cancelado no se guarda: tampoco su archivo, que tiene datos personales. */
    @Transactional
    public void descartar(Long movimientoId) {
        recibos.findByMovimientoId(movimientoId).ifPresent(recibo -> {
            archivos.borrar(recibo.getArchivo());
            recibos.delete(recibo);
        });
    }

    // --- Consulta ---

    @Transactional(readOnly = true)
    public Optional<VistaRecibo> vista(Long movimientoId) {
        return recibos.findByMovimientoId(movimientoId).map(this::vista);
    }

    /** Los recibos con ingreso guardado y período entre los dos meses (incluidos), del más viejo al más nuevo. */
    @Transactional(readOnly = true)
    public List<VistaRecibo> guardadosEntre(YearMonth desde, YearMonth hasta) {
        return recibos.guardadosEntre(desde.atDay(1), hasta.atDay(1)).stream().map(this::vista).toList();
    }

    /** El archivo del recibo de un ingreso (un PDF o un JPEG, según la extensión). */
    @Transactional(readOnly = true)
    public Optional<Path> archivo(Long movimientoId) {
        return recibos.findByMovimientoId(movimientoId).map(ReciboSueldo::getArchivo).flatMap(archivos::archivo);
    }

    private VistaRecibo vista(ReciboSueldo r) {
        List<Renglon> conceptos = r.getConceptos().stream()
                .map(c -> new Renglon(c.getDescripcion(), c.getGrupo(), c.getImporte())).toList();
        return new VistaRecibo(r.getId(), r.getMovimientoId(), r.getEmpleador(), r.getPeriodo(), r.getTipo(),
                r.getFechaPago(), r.getBruto(), r.getDescuentos(), r.getNeto(), conceptos, r.getAviso(),
                archivos.archivo(r.getArchivo()).isPresent());
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }
}
