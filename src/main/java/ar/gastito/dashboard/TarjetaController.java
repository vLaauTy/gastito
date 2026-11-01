package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.tarjeta.CicloTarjetaService;

/**
 * El ciclo de la tarjeta: el resumen cerrado por pagar, el próximo (cuánto llevo), las cuotas comprometidas y las
 * compras de cada uno. Las fechas y el total del resumen se cargan acá o por el bot; todo lo suma Java.
 */
@RestController
@RequestMapping("/api/tarjeta")
public class TarjetaController {

    private final CicloTarjetaService ciclo;

    public TarjetaController(CicloTarjetaService ciclo) {
        this.ciclo = ciclo;
    }

    /** @param panel null si todavía no se cargó ningún cierre (configurada false) */
    public record Tarjeta(boolean configurada, CicloTarjetaService.Panel panel) {
    }

    @GetMapping
    public Tarjeta tarjeta() {
        return ciclo.panel().map(p -> new Tarjeta(true, p)).orElseGet(() -> new Tarjeta(false, null));
    }

    public record FechasRequest(LocalDate cierre, LocalDate vencimiento) {
    }

    @PostMapping("/fechas")
    public CicloTarjetaService.FechasCargadas fechas(@RequestBody FechasRequest pedido) {
        if (pedido == null || pedido.cierre() == null || pedido.vencimiento() == null) {
            throw new IllegalArgumentException("Faltan el cierre o el vencimiento");
        }
        return ciclo.cargarFechas(pedido.cierre(), pedido.vencimiento());
    }

    public record TotalRequest(BigDecimal total) {
    }

    /** El total que dice el resumen del banco; la respuesta dice cuánto falta (o sobra) contra lo cargado. */
    @PostMapping("/{id}/total")
    public CicloTarjetaService.TotalCargado total(@PathVariable long id, @RequestBody TotalRequest pedido) {
        if (pedido == null || pedido.total() == null || pedido.total().signum() < 0) {
            throw new IllegalArgumentException("El total tiene que ser un número positivo");
        }
        return ciclo.cargarTotal(id, pedido.total());
    }
}
