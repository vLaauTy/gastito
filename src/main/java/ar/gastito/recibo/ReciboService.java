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
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Periodo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.ImagenTicket;

/**
 * El recibo de sueldo como control, no como registro: lo lee con la IA (el texto del PDF, o la imagen si es un
 * escaneo o una foto), lo valida en Java, lo guarda con su archivo y lo compara con lo cobrado, con el cálculo del
 * mes y con el recibo anterior. El ingreso lo carga el usuario cuando cobra; el recibo llega una semana después.
 */
@Service
public class ReciboService {

    private static final Logger log = LoggerFactory.getLogger(ReciboService.class);

    private final LectorPdf pdfs;
    private final ImagenTicket imagenes;
    private final LectorRecibo lector;
    private final ValidadorRecibo validador;
    private final MapeadorConceptos mapeador;
    private final ControlRecibo control;
    private final SueldoService sueldo;
    private final ReciboRepository recibos;
    private final ArchivosRecibo archivos;
    private final TransactionTemplate transaccion;
    private final DataSize tamanioMaximo;
    private final Clock clock;

    public ReciboService(LectorPdf pdfs, ImagenTicket imagenes, LectorRecibo lector, ValidadorRecibo validador,
            MapeadorConceptos mapeador, ControlRecibo control, SueldoService sueldo, ReciboRepository recibos,
            ArchivosRecibo archivos, GastitoProperties properties, PlatformTransactionManager transacciones,
            Clock clock) {
        this.pdfs = pdfs;
        this.imagenes = imagenes;
        this.lector = lector;
        this.validador = validador;
        this.mapeador = mapeador;
        this.control = control;
        this.sueldo = sueldo;
        this.recibos = recibos;
        this.archivos = archivos;
        this.transaccion = new TransactionTemplate(transacciones);
        this.tamanioMaximo = properties.ticket().tamanioMaximo();
        this.clock = clock;
    }

    public sealed interface Resultado {
    }

    /** El recibo guardado, con su control. {@code reemplazo}: ya había uno del mismo mes y tipo y se lo cambió. */
    public record Leido(VistaRecibo recibo, boolean reemplazo) implements Resultado {
    }

    public record NoEsRecibo(TipoDocumento documento) implements Resultado {
    }

    public record Ilegible(ValidadorRecibo.Motivo motivo) implements Resultado {
    }

    /**
     * Un renglón del recibo; el importe de un descuento es positivo y el grupo dice que resta.
     *
     * @param conceptoId el concepto del sueldo al que corresponde; null si es "otro"
     */
    public record Renglon(String descripcion, GrupoConcepto grupo, BigDecimal importe, Long conceptoId) {
    }

    /**
     * El sueldo cobrado que corresponde al recibo.
     *
     * @param aMano si se lo vinculó a mano (si no, se lo encontró por el período)
     */
    public record Cobro(Long movimientoId, BigDecimal monto, LocalDateTime fecha, boolean aMano) {
    }

    /**
     * @param cobro    null si todavía no se cargó el sueldo de ese período
     * @param calculado el neto total del cálculo del mes, o null si no hay cálculo
     */
    public record VistaRecibo(Long id, Cobro cobro, String empleador, YearMonth periodo, TipoRecibo tipo,
            LocalDate fechaPago, BigDecimal bruto, BigDecimal descuentos, BigDecimal neto, List<Renglon> conceptos,
            String aviso, boolean conArchivo, BigDecimal calculado, ControlRecibo.Resultado control) {
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
            case ValidadorRecibo.Valido v -> guardar(v.recibo(), archivo, extension, true);
            case ValidadorRecibo.NoEsRecibo n -> new NoEsRecibo(n.documento());
            case ValidadorRecibo.Ilegible i -> new Ilegible(i.motivo());
        };
    }

    /**
     * Guarda un recibo ya validado con su archivo (si hay). Si ya había uno del mismo período y tipo, lo reemplaza
     * (y borra su archivo). Después de leer cada renglón decide a qué concepto del sueldo corresponde.
     *
     * @param archivo el PDF o la imagen, o null si no hay archivo (datos de prueba)
     * @param conIa   si puede preguntarle a la IA qué concepto es cada renglón que no reconoce
     */
    public Leido guardar(ReciboLeido leido, byte[] archivo, String extension, boolean conIa) {
        // La IA se consulta antes de abrir la transacción: puede tardar.
        List<Long> mapeados = mapeador.mapear(leido.conceptos(), sueldo.listarConceptos(), conIa);
        return transaccion.execute(estado -> persistir(leido, mapeados, archivo, extension));
    }

    private Leido persistir(ReciboLeido leido, List<Long> mapeados, byte[] archivo, String extension) {
        List<ReciboSueldo> anteriores = recibos.findByPeriodoAndTipo(leido.periodo().atDay(1), leido.tipo());
        anteriores.forEach(anterior -> archivos.borrar(anterior.getArchivo()));
        recibos.deleteAll(anteriores);
        recibos.flush();

        ReciboSueldo recibo = recibos.save(new ReciboSueldo(leido, mapeados));
        if (archivo != null) {
            try {
                recibo.asociarArchivo(archivos.guardar(recibo.getId(), leido.periodo(), extension, archivo));
            } catch (IOException e) {
                // Sin el archivo el recibo sirve igual: solo no se va a poder abrir desde el dashboard.
                log.warn("No se pudo guardar el archivo del recibo {}: {}", recibo.getId(), e.getMessage());
            }
        }
        return new Leido(vista(recibo), !anteriores.isEmpty());
    }

    // --- Cambios ---

    /** Un recibo descartado no se guarda: tampoco su archivo, que tiene datos personales. */
    @Transactional
    public void descartar(Long reciboId) {
        ReciboSueldo recibo = buscar(reciboId);
        archivos.borrar(recibo.getArchivo());
        recibos.delete(recibo);
    }

    /**
     * Vincula el recibo a mano con un sueldo cobrado (por ejemplo, el que se cobró en otro mes que el período).
     *
     * @param movimientoId el ingreso guardado, o null para volver a buscarlo por período
     * @throws IllegalArgumentException si no es un ingreso guardado
     */
    @Transactional
    public VistaRecibo vincular(Long reciboId, Long movimientoId) {
        ReciboSueldo recibo = buscar(reciboId);
        if (movimientoId != null && recibos.ingresoGuardado(movimientoId).isEmpty()) {
            throw new IllegalArgumentException("Ese no es un ingreso guardado");
        }
        recibo.vincular(movimientoId);
        return vista(recibo);
    }

    // --- Consulta ---

    @Transactional(readOnly = true)
    public Optional<VistaRecibo> vista(Long reciboId) {
        return recibos.findById(reciboId).map(this::vista);
    }

    /** Los recibos con período entre los dos meses (incluidos), del más viejo al más nuevo. */
    @Transactional(readOnly = true)
    public List<VistaRecibo> entre(YearMonth desde, YearMonth hasta) {
        return recibos.entre(desde.atDay(1), hasta.atDay(1)).stream().map(this::vista).toList();
    }

    /** El recibo de un período y tipo, si lo hay. */
    @Transactional(readOnly = true)
    public Optional<VistaRecibo> delPeriodo(YearMonth periodo, TipoRecibo tipo) {
        return recibos.findByPeriodoAndTipo(periodo.atDay(1), tipo).stream().findFirst().map(this::vista);
    }

    /** El archivo de un recibo (un PDF o un JPEG, según la extensión). */
    @Transactional(readOnly = true)
    public Optional<Path> archivo(Long reciboId) {
        return recibos.findById(reciboId).map(ReciboSueldo::getArchivo).flatMap(archivos::archivo);
    }

    /** El sueldo (o aguinaldo) cobrado que corresponde a un recibo: el vinculado a mano o el del período. */
    @Transactional(readOnly = true)
    public Optional<Cobro> cobroDe(Long reciboId) {
        return Optional.ofNullable(cobro(buscar(reciboId)));
    }

    private Cobro cobro(ReciboSueldo r) {
        if (r.getMovimientoId() != null) {
            Optional<Movimiento> vinculado = recibos.ingresoGuardado(r.getMovimientoId());
            if (vinculado.isPresent()) {
                return cobro(vinculado.get(), true);
            }
        }
        // Las vacaciones y las liquidaciones finales no se buscan solas: se confundirían con el sueldo del mes.
        if (!r.getTipo().reemplazaCargaManual()) {
            return null;
        }
        Periodo mes = Periodo.delMes(r.getPeriodo());
        return recibos.ingresosGuardados(r.getTipo().claseIngreso(), mes.desde(), mes.hasta()).stream()
                .findFirst().map(m -> cobro(m, false)).orElse(null);
    }

    private static Cobro cobro(Movimiento m, boolean aMano) {
        return new Cobro(m.getId(), m.getMonto(), m.getFechaHora(), aMano);
    }

    private VistaRecibo vista(ReciboSueldo r) {
        List<Renglon> conceptos = renglones(r);
        Cobro cobro = cobro(r);
        Optional<CalculoSueldo> calculo = r.getTipo() == TipoRecibo.SUELDO_MENSUAL
                ? sueldo.calculoDelMes(r.getPeriodo()) : Optional.empty();
        List<Renglon> anterior = recibos
                .findFirstByPeriodoLessThanAndTipoOrderByPeriodoDescIdDesc(r.getPeriodo().atDay(1), r.getTipo())
                .filter(a -> a.getPeriodo().equals(r.getPeriodo().minusMonths(1))).map(this::renglones).orElse(null);
        ControlRecibo.Resultado resultado = control.revisar(new ControlRecibo.Datos(r.getTipo(), r.getNeto(),
                r.getAviso(), conceptos, cobro == null ? null : cobro.monto(), calculo.orElse(null), anterior));
        return new VistaRecibo(r.getId(), cobro, r.getEmpleador(), r.getPeriodo(), r.getTipo(), r.getFechaPago(),
                r.getBruto(), r.getDescuentos(), r.getNeto(), conceptos, r.getAviso(),
                archivos.archivo(r.getArchivo()).isPresent(), calculo.map(CalculoSueldo::getNetoTotal).orElse(null),
                resultado);
    }

    private List<Renglon> renglones(ReciboSueldo r) {
        return r.getConceptos().stream()
                .map(c -> new Renglon(c.getDescripcion(), c.getGrupo(), c.getImporte(), c.getConceptoSueldoId()))
                .toList();
    }

    private ReciboSueldo buscar(Long id) {
        return recibos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el recibo " + id));
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }
}
