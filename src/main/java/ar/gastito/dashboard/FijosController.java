package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.PagoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.movimiento.Origen;

/** El checklist mensual de gastos fijos y su ABM, para la sección "Fijos" del dashboard. */
@RestController
@RequestMapping("/api/fijos")
public class FijosController {

    private final GastoFijoService fijos;
    private final PagoFijoService pagos;
    private final CatalogoService catalogos;
    private final Clock clock;

    public FijosController(GastoFijoService fijos, PagoFijoService pagos, CatalogoService catalogos, Clock clock) {
        this.fijos = fijos;
        this.pagos = pagos;
        this.catalogos = catalogos;
        this.clock = clock;
    }

    // --- Checklist ---

    public record ChecklistItem(long fijoId, String nombre, String emoji, String estado, BigDecimal monto,
            LocalDate fecha, BigDecimal montoAnterior, BigDecimal porcentaje, LocalDate vencimiento,
            boolean proponerMonto, Long movimientoId, boolean estimado, BigDecimal aproximado) {
    }

    /** @param estimado cuánto de lo cargado es monto estimado (tildado con "Pagado" y sin corregir) */
    public record Checklist(String mes, List<ChecklistItem> items, BigDecimal cargado, BigDecimal estimado,
            int faltan) {
    }

    @GetMapping("/checklist")
    public Checklist checklist(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        YearMonth m = mesOActual(mes);
        List<GastoFijoService.ItemChecklist> items = fijos.checklist(m);
        BigDecimal cargado = items.stream().filter(i -> i.estado() == GastoFijoService.EstadoChecklist.CARGADO)
                .map(GastoFijoService.ItemChecklist::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimado = items.stream().filter(GastoFijoService.ItemChecklist::estimado)
                .map(GastoFijoService.ItemChecklist::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        int faltan = (int) items.stream().filter(i -> i.estado() == GastoFijoService.EstadoChecklist.FALTA).count();
        List<ChecklistItem> dto = items.stream()
                .map(i -> new ChecklistItem(i.fijoId(), i.nombre(), i.emoji(), i.estado().name(), i.monto(),
                        i.fecha(), i.montoAnterior(), i.porcentaje(), i.vencimiento(), i.proponerMonto(),
                        i.movimientoId(), i.estimado(), i.aproximado()))
                .toList();
        return new Checklist(m.toString(), dto, cargado, estimado, faltan);
    }

    public record CargaRequest(BigDecimal monto, LocalDate fecha) {
    }

    /** "Cargar monto" desde una fila del checklist: crea el gasto igual que el bot. */
    @PostMapping("/{id}/cargar")
    public ResponseEntity<Void> cargar(@PathVariable long id, @RequestBody CargaRequest pedido) {
        LocalDate fecha = pedido.fecha() == null ? LocalDate.now(clock) : pedido.fecha();
        fijos.cargarManual(id, pedido.monto(), fecha.atTime(LocalTime.now(clock)), Origen.DASHBOARD, null);
        return ResponseEntity.noContent().build();
    }

    /** "Pagado": el gasto del mes con el monto aproximado, marcado como estimado. */
    @PostMapping("/{id}/pagado")
    public ResponseEntity<Void> pagado(@PathVariable long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        pagos.pagado(id, mes, Origen.DASHBOARD);
        return ResponseEntity.noContent().build();
    }

    public record MontoRequest(BigDecimal monto) {
    }

    /** "Corregir monto": cambia el monto del mismo gasto (y deja de ser estimado). */
    @PutMapping("/cobros/{movimientoId}/monto")
    public ResponseEntity<Void> corregirMonto(@PathVariable long movimientoId, @RequestBody MontoRequest pedido) {
        pagos.corregirMonto(movimientoId, pedido.monto());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/este-mes-no")
    public ResponseEntity<Void> esteMesNo(@PathVariable long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        fijos.esteMesNoDelPeriodo(id, mes);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deshacer")
    public ResponseEntity<Void> deshacer(@PathVariable long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        fijos.deshacer(id, mes);
        return ResponseEntity.noContent().build();
    }

    public record MesHistorial(String mes, BigDecimal monto) {
    }

    @GetMapping("/{id}/historial")
    public List<MesHistorial> historial(@PathVariable long id) {
        return fijos.historial(id).stream().map(h -> new MesHistorial(h.mes().toString(), h.monto())).toList();
    }

    // --- ABM ---

    public record FijoAbm(long id, String nombre, BigDecimal montoEsperado, Long categoriaId, String categoria,
            Long medioPagoId, Long cuentaId, boolean tarjeta, Integer diaDelMes, String frecuencia,
            boolean montoVariable, String alias, boolean proponerMonto, boolean activo) {
    }

    @GetMapping
    public List<FijoAbm> todos() {
        return fijos.todosParaAbm().stream().map(FijosController::aAbm).toList();
    }

    private static FijoAbm aAbm(GastoFijoService.VistaAbm f) {
        return new FijoAbm(f.id(), f.nombre(), f.montoEsperado(), f.categoria().getId(), f.categoria().getNombre(),
                f.medioPago() == null ? null : f.medioPago().getId(), f.cuenta() == null ? null : f.cuenta().getId(),
                f.tarjeta() != null, f.diaDelMes(), f.frecuencia().name(), f.montoVariable(), f.alias(),
                f.proponerMonto(), f.activo());
    }

    public record NuevoFijoRequest(String nombre, BigDecimal monto, Long categoriaId, Long medioPagoId,
            Long cuentaId, boolean tarjeta, Integer diaDelMes, String frecuencia, boolean montoVariable, String alias,
            boolean proponerMonto) {
    }

    @PostMapping
    public ResponseEntity<VistaFijo> alta(@RequestBody NuevoFijoRequest pedido) {
        VistaFijo f = fijos.alta(aNuevoFijo(pedido));
        return ResponseEntity.ok(f);
    }

    @PutMapping("/{id}")
    public VistaFijo editar(@PathVariable long id, @RequestBody NuevoFijoRequest pedido) {
        return fijos.editar(id, aNuevoFijo(pedido));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> baja(@PathVariable long id) {
        fijos.darDeBaja(id);
        return ResponseEntity.noContent().build();
    }

    private GastoFijoService.NuevoFijo aNuevoFijo(NuevoFijoRequest p) {
        Catalogo catalogo = catalogos.cargar();
        Categoria categoria = catalogo.categorias().stream().filter(c -> c.getId() == p.categoriaId())
                .findFirst().orElseThrow(() -> new NoSuchElementException("No existe la categoría " + p.categoriaId()));
        MedioPago medioPago = p.medioPagoId() == null ? null
                : catalogo.mediosPago().stream().filter(m -> m.getId() == p.medioPagoId()).findFirst()
                        .orElseThrow(() -> new NoSuchElementException("No existe el medio de pago " + p.medioPagoId()));
        Cuenta cuenta = p.cuentaId() == null ? null
                : catalogo.cuentas().stream().filter(c -> c.getId() == p.cuentaId()).findFirst()
                        .orElseThrow(() -> new NoSuchElementException("No existe la cuenta " + p.cuentaId()));
        Frecuencia frecuencia = frecuencia(p.frecuencia());
        return new GastoFijoService.NuevoFijo(p.nombre(), p.monto(), categoria, medioPago, cuenta,
                p.tarjeta() ? catalogo.tarjetaCredito().orElse(null) : null, p.diaDelMes(), frecuencia,
                p.montoVariable(), p.alias(), p.proponerMonto());
    }

    private static Frecuencia frecuencia(String valor) {
        try {
            return Frecuencia.valueOf(valor == null ? "" : valor.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La frecuencia tiene que ser MENSUAL o BIMESTRAL");
        }
    }

    private YearMonth mesOActual(YearMonth mes) {
        return mes != null ? mes : YearMonth.now(clock);
    }
}
