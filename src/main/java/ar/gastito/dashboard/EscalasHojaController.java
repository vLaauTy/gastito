package ar.gastito.dashboard;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

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
import org.springframework.web.multipart.MultipartFile;

import ar.gastito.sueldo.escala.AliasEscala;
import ar.gastito.sueldo.escala.DestinoAlias;
import ar.gastito.sueldo.escala.HojaEscala;
import ar.gastito.sueldo.escala.ImportacionEscala;
import ar.gastito.sueldo.escala.ImportacionesPendientes;
import ar.gastito.sueldo.escala.LectorExcelEscala;
import ar.gastito.sueldo.escala.MesHoja;
import ar.gastito.sueldo.escala.PlantillaEscala;

/**
 * La escala desde la hoja del sindicato en el dashboard: subir el Excel, ver el resumen por mes y guardar los meses
 * elegidos; descargar la plantilla con la escala vigente, y los nombres de fila (alias) que reconoce cada concepto.
 */
@RestController
@RequestMapping("/api/sueldo")
public class EscalasHojaController {

    static final long TAMANIO_MAXIMO = 5L * 1024 * 1024;
    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final LectorExcelEscala lector;
    private final ImportacionEscala importacion;
    private final ImportacionesPendientes pendientes;
    private final PlantillaEscala plantilla;
    private final Clock clock;

    public EscalasHojaController(LectorExcelEscala lector, ImportacionEscala importacion,
            ImportacionesPendientes pendientes, PlantillaEscala plantilla, Clock clock) {
        this.lector = lector;
        this.importacion = importacion;
        this.pendientes = pendientes;
        this.plantilla = plantilla;
        this.clock = clock;
    }

    // --- Importación ---

    /** @param etiqueta el mes como lo escribe la hoja ("oct-26") */
    public record Mes(YearMonth mes, String etiqueta, BigDecimal valorHora, List<ImportacionEscala.Valor> leidos,
            List<ImportacionEscala.Valor> mantenidos, ImportacionEscala.Control control, boolean reemplaza) {
    }

    public record Importacion(long id, List<Mes> meses, List<String> ignoradas) {
    }

    public record PedidoGuardar(List<String> meses) {
    }

    public record Guardada(YearMonth mes, String etiqueta, Long escalaId, boolean reemplazada) {
    }

    /**
     * Lee el Excel y arma el resumen, sin guardar nada.
     *
     * @param mes para la plantilla de un solo mes que no dice de qué mes es (AAAA-MM)
     */
    @PostMapping(path = "/escalas/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Importacion importar(@RequestParam("archivo") MultipartFile archivo,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) throws IOException {
        if (archivo.isEmpty()) {
            throw new IllegalArgumentException("Elegí el Excel con la escala");
        }
        if (archivo.getSize() > TAMANIO_MAXIMO) {
            throw new IllegalArgumentException("El Excel pesa más de 5 MB");
        }
        HojaEscala hoja = lector.leer(archivo.getBytes());
        if (hoja.faltaElMes()) {
            if (mes == null) {
                throw new IllegalArgumentException("La planilla no dice de qué mes es: elegí el mes y subila de nuevo");
            }
            hoja = hoja.conMes(mes);
        }
        ImportacionEscala.Propuesta propuesta = importacion.proponer(hoja);
        ImportacionesPendientes.Pendiente p = pendientes.agregar(hoja, propuesta);
        return new Importacion(p.id(), propuesta.meses().stream().map(m -> new Mes(m.mes(), MesHoja.corto(m.mes()),
                m.valorHora(), m.leidos(), m.mantenidos(), m.control(), m.reemplaza())).toList(),
                propuesta.ignoradas());
    }

    /** Guarda los meses elegidos (crea la escala de cada uno o reemplaza la que había). */
    @PostMapping("/escalas/importaciones/{id}")
    public List<Guardada> guardar(@PathVariable long id, @RequestBody PedidoGuardar pedido) {
        ImportacionesPendientes.Pendiente p = pendientes.buscar(id).filter(x -> x.propuesta() != null)
                .orElseThrow(() -> new IllegalArgumentException("Esa importación ya no está: subí el Excel de nuevo"));
        List<YearMonth> meses = (pedido.meses() == null ? List.<String>of() : pedido.meses()).stream()
                .map(EscalasHojaController::mes).toList();
        List<Guardada> guardadas = importacion.guardar(p.propuesta(), meses).stream()
                .map(g -> new Guardada(g.mes(), MesHoja.corto(g.mes()), g.escalaId(), g.reemplazada())).toList();
        pendientes.quitar(id);
        return guardadas;
    }

    @DeleteMapping("/escalas/importaciones/{id}")
    public ResponseEntity<Void> descartar(@PathVariable long id) {
        pendientes.quitar(id);
        return ResponseEntity.noContent().build();
    }

    /** La plantilla en Excel, con el formato de la hoja del sindicato y la escala vigente. */
    @GetMapping("/escalas/plantilla")
    public ResponseEntity<byte[]> plantilla(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        YearMonth m = mes != null ? mes : YearMonth.now(clock);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("escala-" + m + ".xlsx").build().toString())
                .body(plantilla.generar(m));
    }

    private static YearMonth mes(String texto) {
        try {
            return YearMonth.parse(texto);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("El mes tiene que ser AAAA-MM");
        }
    }

    // --- Alias ---

    public record Alias(Long id, String alias, String destino, Long conceptoId) {
    }

    public record PedidoAlias(String alias, String destino, Long conceptoId) {
    }

    @GetMapping("/alias")
    public List<Alias> alias() {
        return importacion.listarAlias().stream().map(EscalasHojaController::alias).toList();
    }

    @PostMapping("/alias")
    public Alias crearAlias(@RequestBody PedidoAlias p) {
        DestinoAlias destino;
        try {
            destino = DestinoAlias.valueOf(p.destino());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Elegí a qué corresponde la fila");
        }
        return alias(importacion.crearAlias(p.alias(), destino, p.conceptoId()));
    }

    @DeleteMapping("/alias/{id}")
    public ResponseEntity<Void> borrarAlias(@PathVariable long id) {
        importacion.borrarAlias(id);
        return ResponseEntity.noContent().build();
    }

    private static Alias alias(AliasEscala a) {
        return new Alias(a.getId(), a.getAlias(), a.getDestino().name(), a.getConceptoId());
    }
}
