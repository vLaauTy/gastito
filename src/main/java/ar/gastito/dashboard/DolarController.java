package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.dolar.CompraEnDolaresService;

/**
 * Las compras en dólares con la tarjeta: la cotización de referencia del dólar tarjeta (con la que Java estima los
 * pesos) y "Corregir monto" con lo que cobraron según el resumen.
 */
@RestController
@RequestMapping("/api/dolar")
public class DolarController {

    private final CompraEnDolaresService compras;

    public DolarController(CompraEnDolaresService compras) {
        this.compras = compras;
    }

    /** @param valor pesos por dólar, o null si todavía no se cargó ninguna */
    public record Cotizacion(BigDecimal valor, LocalDateTime cargadaEn) {
    }

    @GetMapping
    public Cotizacion cotizacion() {
        return compras.referencia().map(c -> new Cotizacion(c.getValor(), c.getCargadaEn()))
                .orElseGet(() -> new Cotizacion(null, null));
    }

    public record CotizacionRequest(BigDecimal valor) {
    }

    /** Las compras que estaban sin estimar se estiman con esta; las demás siguen con la de su momento. */
    @PostMapping
    public Cotizacion cargar(@RequestBody CotizacionRequest pedido) {
        if (pedido == null || pedido.valor() == null || pedido.valor().signum() <= 0) {
            throw new IllegalArgumentException("La cotización tiene que ser un número positivo");
        }
        var c = compras.cargarCotizacion(pedido.valor()).cotizacion();
        return new Cotizacion(c.getValor(), c.getCargadaEn());
    }

    public record PesosRequest(BigDecimal monto) {
    }

    /** "Corregir monto": lo que cobraron en pesos. Deja de ser estimado y queda la cotización real. */
    @PutMapping("/compras/{id}/pesos")
    public ConsultaMovimientos.Fila corregir(@PathVariable long id, @RequestBody PesosRequest pedido) {
        if (pedido == null || pedido.monto() == null || pedido.monto().signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser un número positivo");
        }
        return ConsultaMovimientos.fila(compras.corregirPesos(id, pedido.monto()));
    }
}
