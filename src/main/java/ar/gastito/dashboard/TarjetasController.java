package ar.gastito.dashboard;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.TarjetaCredito;

/** Las tarjetas de crédito: agregar, editar (nombre, emisor, cuenta que paga, alias, color, inicial) y dar de baja. */
@RestController
@RequestMapping("/api/tarjetas")
public class TarjetasController {

    private final CatalogoService catalogos;

    public TarjetasController(CatalogoService catalogos) {
        this.catalogos = catalogos;
    }

    /** @param alias separados por coma, como se editan */
    public record Tarjeta(long id, String nombre, String banco, Long cuentaDebitoId, String cuentaDebito,
            String color, String inicial, String alias, boolean activa) {

        static Tarjeta de(TarjetaCredito t) {
            return new Tarjeta(t.getId(), t.getNombre(), t.getBanco(),
                    t.getCuentaDebito() == null ? null : t.getCuentaDebito().getId(),
                    t.getCuentaDebito() == null ? null : t.getCuentaDebito().getNombre(), t.getColor(),
                    t.getInicial(), String.join(", ", t.alias()), t.isActiva());
        }
    }

    @GetMapping
    public List<Tarjeta> todas() {
        return catalogos.todasLasTarjetas().stream().map(Tarjeta::de).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Tarjeta alta(@RequestBody CatalogoService.DatosTarjeta datos) {
        return Tarjeta.de(catalogos.altaTarjeta(datos));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Tarjeta editar(@PathVariable long id, @RequestBody CatalogoService.DatosTarjeta datos) {
        return Tarjeta.de(catalogos.editarTarjeta(id, datos));
    }

    @PostMapping("/{id}/baja")
    public Tarjeta baja(@PathVariable long id) {
        return Tarjeta.de(catalogos.bajaTarjeta(id));
    }

    @PostMapping("/{id}/reactivar")
    public Tarjeta reactivar(@PathVariable long id) {
        return Tarjeta.de(catalogos.reactivarTarjeta(id));
    }
}
