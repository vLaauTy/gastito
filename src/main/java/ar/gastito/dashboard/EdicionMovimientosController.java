package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.interpretacion.Montos;

/**
 * Editar y eliminar un gasto guardado desde la tabla de movimientos. Una juntada, una cuota o el pago de un fijo se
 * borran con {@code ?alcance=JUNTADA|CUOTAS|FIJO}; sin el alcance que corresponde, 409 con la explicación.
 */
@RestController
@RequestMapping("/api/movimientos")
public class EdicionMovimientosController {

    private final EdicionMovimientos edicion;

    public EdicionMovimientosController(EdicionMovimientos edicion) {
        this.edicion = edicion;
    }

    /**
     * @param monto       como lo escribe el usuario: "3.700", "3700,50", "4 mil"
     * @param fecha       el día; la hora queda la que tenía
     * @param medioPagoId null es "sin especificar"
     * @param cuentaId    null es sin cuenta
     */
    public record Cambio(String monto, LocalDate fecha, String descripcion, Long categoriaId, Long medioPagoId,
            Long cuentaId) {
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConsultaMovimientos.Fila editar(@PathVariable long id, @RequestBody Cambio cambio) {
        BigDecimal monto = Montos.parsearConPalabras(cambio.monto())
                .orElseThrow(() -> new IllegalArgumentException("No entendí el monto. Por ejemplo: 3.700"));
        return edicion.editar(id, monto, cambio.fecha(), cambio.descripcion(), cambio.categoriaId(),
                cambio.medioPagoId(), cambio.cuentaId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> borrar(@PathVariable long id,
            @RequestParam(defaultValue = "GASTO") EdicionMovimientos.Alcance alcance) {
        edicion.borrar(id, alcance);
        return ResponseEntity.noContent().build();
    }
}
