package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.recibo.AnalisisRecibos;
import ar.gastito.recibo.ReciboService;

/**
 * Los recibos de sueldo en la sección Ingresos: el detalle de conceptos de un ingreso, el archivo (solo se ve desde
 * esta PC o con el token del dashboard) y la serie de los últimos meses con sus variaciones.
 */
@RestController
@RequestMapping("/api/recibos")
public class RecibosController {

    static final int MESES_DE_LA_SERIE = 12;
    static final int MAXIMO_MESES = 36;

    private final ReciboService recibos;
    private final AnalisisRecibos analisis;
    private final Clock clock;

    public RecibosController(ReciboService recibos, AnalisisRecibos analisis, Clock clock) {
        this.recibos = recibos;
        this.analisis = analisis;
        this.clock = clock;
    }

    /** Un renglón del recibo; el importe de un descuento es positivo y el grupo dice que resta. */
    public record Concepto(String descripcion, String grupo, String grupoNombre, BigDecimal importe) {
    }

    /**
     * @param aviso lo que no cerró al leer el recibo, o null si cerró todo
     */
    public record Detalle(Long movimientoId, String empleador, YearMonth periodo, String tipo, String tipoNombre,
            LocalDate fechaPago, BigDecimal bruto, BigDecimal descuentos, BigDecimal neto, String aviso,
            boolean conArchivo, List<Concepto> conceptos) {
    }

    /** 404 si el ingreso no vino de un recibo. */
    @GetMapping("/{movimientoId}")
    public Detalle detalle(@PathVariable long movimientoId) {
        ReciboService.VistaRecibo r = recibos.vista(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("El ingreso " + movimientoId + " no tiene recibo"));
        return new Detalle(r.movimientoId(), r.empleador(), r.periodo(), r.tipo().name(), r.tipo().getNombre(),
                r.fechaPago(), r.bruto(), r.descuentos(), r.neto(), r.aviso(), r.conArchivo(),
                r.conceptos().stream().map(c -> new Concepto(c.descripcion(), c.grupo().name(),
                        c.grupo().getNombre(), c.importe())).toList());
    }

    /** El recibo tal como se guardó: el PDF original o la foto achicada. */
    @GetMapping("/{movimientoId}/archivo")
    public ResponseEntity<Resource> archivo(@PathVariable long movimientoId) {
        Path archivo = recibos.archivo(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("El ingreso " + movimientoId + " no tiene archivo"));
        boolean pdf = archivo.getFileName().toString().endsWith(".pdf");
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("recibo-" + movimientoId + (pdf ? ".pdf" : ".jpg")).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(archivo));
    }

    /** Bruto, descuentos y neto de los últimos meses (solo sueldos mensuales) y la variación del último. */
    @GetMapping("/serie")
    public AnalisisRecibos.Serie serie(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth hasta,
            @RequestParam(defaultValue = "" + MESES_DE_LA_SERIE) int meses) {
        if (meses < 1 || meses > MAXIMO_MESES) {
            throw new IllegalArgumentException("meses va de 1 a " + MAXIMO_MESES);
        }
        return analisis.serie(hasta != null ? hasta : YearMonth.now(clock), meses);
    }
}
