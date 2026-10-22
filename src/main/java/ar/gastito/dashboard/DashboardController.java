package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
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

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.interpretacion.Montos;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;

/**
 * La API del dashboard (y más adelante de la Mini App). Devuelve JSON con los números ya calculados.
 * Todos los GET aceptan {@code ?mes=2026-10}; por defecto, el mes actual. Lo único que escribe es la carga y el
 * borrado de ingresos.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    static final int MESES_DE_INGRESOS = 6;
    static final int MAXIMO_MESES_DE_INGRESOS = 24;

    private final DashboardService dashboard;
    private final ConsultaMovimientos movimientos;
    private final IngresoService ingresos;
    private final CatalogoService catalogos;
    private final Clock clock;

    public DashboardController(DashboardService dashboard, ConsultaMovimientos movimientos, IngresoService ingresos,
            CatalogoService catalogos, Clock clock) {
        this.dashboard = dashboard;
        this.movimientos = movimientos;
        this.ingresos = ingresos;
        this.catalogos = catalogos;
        this.clock = clock;
    }

    @GetMapping("/resumen")
    public DashboardService.Resumen resumen(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return dashboard.resumen(mesOActual(mes));
    }

    @GetMapping("/gastos/por-dia")
    public DashboardService.PorDia porDia(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return dashboard.porDia(mesOActual(mes));
    }

    @GetMapping("/gastos/por-categoria")
    public List<DashboardService.CategoriaDelMes> porCategoria(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return dashboard.porCategoria(mesOActual(mes));
    }

    @GetMapping("/gastos/por-billetera")
    public List<DashboardService.Billetera> porBilletera(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return dashboard.porBilletera(mesOActual(mes));
    }

    @GetMapping("/movimientos")
    public ConsultaMovimientos.Pagina movimientos(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes,
            @RequestParam(required = false) Long categoria,
            @RequestParam(required = false) Long medioPago,
            @RequestParam(required = false) Origen origen,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanio) {
        return movimientos.buscar(new ConsultaMovimientos.Filtros(mesOActual(mes), categoria, medioPago, origen, q),
                pagina, tamanio);
    }

    @GetMapping("/movimientos.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        YearMonth m = mesOActual(mes);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("gastito-" + m + ".csv").build().toString())
                .body(movimientos.csv(m));
    }

    @GetMapping("/costos-ia")
    public DashboardService.CostosIa costosIa(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return dashboard.costosIa(mesOActual(mes));
    }

    // --- Catálogo, para los filtros y el formulario de ingresos ---

    public record Opcion(Long id, String nombre, String emoji) {
    }

    public record Clase(String clave, String nombre) {
    }

    public record CatalogoDashboard(List<Opcion> categorias, List<Opcion> mediosPago, List<Opcion> cuentas,
            List<Clase> clasesIngreso) {
    }

    @GetMapping("/catalogo")
    public CatalogoDashboard catalogo() {
        Catalogo c = catalogos.cargar();
        return new CatalogoDashboard(
                c.categorias().stream().map(x -> new Opcion(x.getId(), x.getNombre(), x.getEmoji())).toList(),
                c.mediosPago().stream().map(x -> new Opcion(x.getId(), x.getNombre(), null)).toList(),
                c.cuentas().stream().map(x -> new Opcion(x.getId(), x.getNombre(), null)).toList(),
                Arrays.stream(ClaseIngreso.values()).map(x -> new Clase(x.name(), x.getNombre())).toList());
    }

    // --- Ingresos ---

    @GetMapping("/ingresos")
    public DashboardService.IngresosDelMes ingresos(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return dashboard.ingresosDelMes(mesOActual(mes));
    }

    @GetMapping("/ingresos/por-mes")
    public List<DashboardService.IngresoMensual> ingresosPorMes(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth hasta,
            @RequestParam(defaultValue = "" + MESES_DE_INGRESOS) int meses) {
        if (meses < 1 || meses > MAXIMO_MESES_DE_INGRESOS) {
            throw new IllegalArgumentException("meses va de 1 a " + MAXIMO_MESES_DE_INGRESOS);
        }
        return dashboard.ingresosPorMes(mesOActual(hasta), meses);
    }

    /**
     * @param monto    como lo escribe el usuario: "380.000", "380 mil"
     * @param cuentaId null si fue en efectivo
     * @param fecha    null es hoy
     */
    public record NuevoIngreso(String clase, String monto, String descripcion, Long cuentaId, LocalDate fecha) {
    }

    @PostMapping(path = "/ingresos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DashboardService.IngresoDelMes> cargarIngreso(@RequestBody NuevoIngreso nuevo) {
        ClaseIngreso clase = claseIngreso(nuevo.clase());
        BigDecimal monto = Montos.parsearConPalabras(nuevo.monto())
                .orElseThrow(() -> new IllegalArgumentException("No entendí el monto. Por ejemplo: 380.000"));
        Movimiento m = ingresos.cargar(clase, monto, nuevo.descripcion(), nuevo.cuentaId(),
                nuevo.fecha() == null ? LocalDate.now(clock) : nuevo.fecha());
        return ResponseEntity.created(URI.create("/api/ingresos/" + m.getId())).body(DashboardService.ingreso(m));
    }

    @DeleteMapping("/ingresos/{id}")
    public ResponseEntity<Void> borrarIngreso(@PathVariable long id) {
        ingresos.anular(id);
        return ResponseEntity.noContent().build();
    }

    private YearMonth mesOActual(YearMonth mes) {
        return mes != null ? mes : YearMonth.now(clock);
    }

    private static ClaseIngreso claseIngreso(String clave) {
        try {
            return ClaseIngreso.valueOf(clave == null ? "" : clave.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Elegí el tipo de ingreso: sueldo, aguinaldo o extra");
        }
    }
}
