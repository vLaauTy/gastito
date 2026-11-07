package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.MedioElegido;
import ar.gastito.prestamo.VistaPrestamo;
import ar.gastito.viaje.PanelViaje;
import ar.gastito.viaje.ViajeService;

/**
 * La pestaña Viajes: un panel por viaje (pagado, falta, total estimado, presupuesto, lo que me deben, los pendientes,
 * los gastos por categoría y por día y cuánto ahorrar, todo calculado en Java) y lo que se carga desde ahí.
 */
@RestController
@RequestMapping("/api/viajes")
public class ViajesController {

    private final ViajeService viajes;
    private final CatalogoService catalogos;

    public ViajesController(ViajeService viajes, CatalogoService catalogos) {
        this.viajes = viajes;
        this.catalogos = catalogos;
    }

    /** Del más reciente al más viejo. */
    @GetMapping
    public List<PanelViaje> todos() {
        return viajes.todos();
    }

    @GetMapping("/{id}")
    public PanelViaje uno(@PathVariable Long id) {
        return viajes.panel(id);
    }

    public record ViajeRequest(String nombre, String emoji, LocalDate desde, LocalDate hasta, Integer personas,
            BigDecimal presupuesto, String notas) {

        ViajeService.Datos datos() {
            return new ViajeService.Datos(nombre, emoji, desde, hasta, personas, presupuesto, notas);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PanelViaje crear(@RequestBody ViajeRequest r) {
        return viajes.crear(r.datos());
    }

    @PutMapping("/{id}")
    public PanelViaje editar(@PathVariable Long id, @RequestBody ViajeRequest r) {
        return viajes.editar(id, r.datos());
    }

    /** Los gastos y préstamos del viaje quedan, sin viaje. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void borrar(@PathVariable Long id) {
        viajes.borrar(id);
    }

    // --- Préstamos ---

    /** Los préstamos guardados que no son de ningún viaje, para asociarlos. */
    @GetMapping("/prestamos-sin-viaje")
    public List<VistaPrestamo> prestamosSinViaje() {
        return viajes.prestamosSinViaje();
    }

    /** @param viajeId null: el préstamo queda sin viaje */
    public record ViajeDelPrestamo(Long viajeId) {
    }

    @PutMapping("/prestamos/{id}")
    public VistaPrestamo viajeDelPrestamo(@PathVariable Long id, @RequestBody ViajeDelPrestamo r) {
        return viajes.asociarPrestamo(id, r.viajeId());
    }

    // --- Pendientes ---

    public record PendienteRequest(String descripcion, BigDecimal montoAproximado, Long categoriaId) {
    }

    @PostMapping("/{id}/pendientes")
    @ResponseStatus(HttpStatus.CREATED)
    public PanelViaje agregarPendiente(@PathVariable Long id, @RequestBody PendienteRequest r) {
        return viajes.agregarPendiente(id, r.descripcion(), r.montoAproximado(), r.categoriaId());
    }

    @PutMapping("/pendientes/{id}")
    public PanelViaje editarPendiente(@PathVariable Long id, @RequestBody PendienteRequest r) {
        return viajes.editarPendiente(id, r.descripcion(), r.montoAproximado(), r.categoriaId());
    }

    @DeleteMapping("/pendientes/{id}")
    public PanelViaje borrarPendiente(@PathVariable Long id) {
        return viajes.borrarPendiente(id);
    }

    /**
     * Se tildó: el gasto del viaje, guardado.
     *
     * @param medioPagoId el medio de pago o −id de una tarjeta (como en los demás formularios); null si no se sabe
     * @param monto       null: el aproximado
     */
    public record PagarRequest(BigDecimal monto, Long categoriaId, Long medioPagoId, LocalDate fecha) {
    }

    @PostMapping("/pendientes/{id}/pagar")
    public PanelViaje pagar(@PathVariable Long id, @RequestBody PagarRequest r) {
        Long medio = null;
        Long tarjeta = null;
        if (r.medioPagoId() != null) {
            MedioElegido elegido = MedioElegido.de(r.medioPagoId(), catalogos.cargar())
                    .orElseThrow(() -> new NoSuchElementException("No existe ese medio de pago"));
            medio = elegido.medio().getId();
            tarjeta = elegido.tarjeta() == null ? null : elegido.tarjeta().getId();
        }
        return viajes.pagarPendiente(id, r.monto(), r.categoriaId(), medio, tarjeta, r.fecha());
    }
}
