package ar.gastito.catalogo;

import java.util.List;
import java.util.Optional;

/** Las opciones reales de la base contra las que se interpreta un mensaje. */
public record Catalogo(List<Categoria> categorias, List<MedioPago> mediosPago, List<Cuenta> cuentas,
        Optional<TarjetaCredito> tarjetaCredito) {

    public Categoria otros() {
        return categorias.stream()
                .filter(c -> Categoria.OTROS.equals(c.getNombre()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Falta la categoría " + Categoria.OTROS));
    }
}
