package ar.gastito.interpretacion;

import java.util.List;
import java.util.Optional;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.Cuenta;
import ar.gastito.movimiento.MedioPago;
import ar.gastito.movimiento.Tarjeta;

/** Las opciones reales de la base contra las que se interpreta un mensaje. */
public record Catalogo(List<Categoria> categorias, List<MedioPago> mediosPago, List<Cuenta> cuentas,
        Optional<Tarjeta> tarjetaCredito) {

    public Categoria otros() {
        return categorias.stream()
                .filter(c -> Categoria.OTROS.equals(c.getNombre()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Falta la categoría " + Categoria.OTROS));
    }
}
