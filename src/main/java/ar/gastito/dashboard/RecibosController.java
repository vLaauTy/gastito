package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.recibo.AnalisisRecibos;
import ar.gastito.recibo.ControlRecibo;
import ar.gastito.recibo.ReciboService;

/**
 * Los recibos de sueldo en la sección Ingresos: el detalle y el control de un recibo, el archivo (solo se ve desde
 * esta PC o con el token del dashboard), vincularlo a un sueldo cobrado y la serie de los últimos meses.
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
    public record Concepto(String descripcion, String grupo, String grupoNombre, BigDecimal importe,
            Long conceptoSueldoId) {
    }

    /** El sueldo cobrado del recibo; {@code aMano} si se lo vinculó a mano. */
    public record Cobro(Long movimientoId, BigDecimal monto, LocalDateTime fecha, boolean aMano) {
    }

    /**
     * @param aviso      lo que no cerró al leer el recibo, o null si cerró todo
     * @param cobro      null si todavía no se cargó el sueldo de ese período
     * @param calculado  el neto total del cálculo del mes, o null si no hay
     * @param hallazgos  lo raro que encontró el control (vacío si no hay nada)
     * @param conCalculo si había un cálculo del mes para comparar los conceptos
     */
    public record Detalle(Long id, String empleador, YearMonth periodo, String tipo, String tipoNombre,
            LocalDate fechaPago, BigDecimal bruto, BigDecimal descuentos, BigDecimal neto, String aviso,
            boolean conArchivo, Cobro cobro, BigDecimal calculado, List<String> hallazgos, boolean conCalculo,
            List<Concepto> conceptos) {
    }

    public record PedidoVincular(Long movimientoId) {
    }

    /** 404 si no existe. */
    @GetMapping("/{id}")
    public Detalle detalle(@PathVariable long id) {
        return detalle(recibos.vista(id).orElseThrow(() -> new NoSuchElementException("No existe el recibo " + id)));
    }

    static Detalle detalle(ReciboService.VistaRecibo r) {
        ReciboService.Cobro c = r.cobro();
        return new Detalle(r.id(), r.empleador(), r.periodo(), r.tipo().name(), r.tipo().getNombre(),
                r.fechaPago(), r.bruto(), r.descuentos(), r.neto(), r.aviso(), r.conArchivo(),
                c == null ? null : new Cobro(c.movimientoId(), c.monto(), c.fecha(), c.aMano()), r.calculado(),
                r.control().hallazgos().stream().map(ControlRecibo.Hallazgo::texto).toList(),
                r.control().conCalculo(),
                r.conceptos().stream().map(x -> new Concepto(x.descripcion(), x.grupo().name(),
                        x.grupo().getNombre(), x.importe(), x.conceptoId())).toList());
    }

    /** El recibo tal como se guardó: el PDF original o la foto achicada. */
    @GetMapping("/{id}/archivo")
    public ResponseEntity<Resource> archivo(@PathVariable long id) {
        Path archivo = recibos.archivo(id)
                .orElseThrow(() -> new NoSuchElementException("El recibo " + id + " no tiene archivo"));
        boolean pdf = archivo.getFileName().toString().endsWith(".pdf");
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("recibo-" + id + (pdf ? ".pdf" : ".jpg")).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(archivo));
    }

    /** Vincula el recibo con un sueldo cobrado; sin {@code movimientoId} vuelve a buscarlo por período. */
    @PostMapping("/{id}/vincular")
    public Detalle vincular(@PathVariable long id, @RequestBody PedidoVincular pedido) {
        return detalle(recibos.vincular(id, pedido.movimientoId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> descartar(@PathVariable long id) {
        recibos.descartar(id);
        return ResponseEntity.noContent().build();
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
