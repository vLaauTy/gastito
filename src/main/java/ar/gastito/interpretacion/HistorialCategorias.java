package ar.gastito.interpretacion;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.DescripcionConfirmada;
import ar.gastito.movimiento.MovimientoRepository;

/**
 * Aprende de los gastos confirmados: si el usuario ya guardó gastos con una descripción igual o muy parecida
 * y siempre los puso en la misma categoría, se usa esa categoría sin preguntar.
 */
@Service
public class HistorialCategorias {

    private final MovimientoRepository movimientos;

    public HistorialCategorias(MovimientoRepository movimientos) {
        this.movimientos = movimientos;
    }

    /** @param descripcion la descripción como la guardó el usuario (para reusarla tal cual) */
    public record Aprendido(Categoria categoria, String descripcion) {
    }

    /** Vacío si no hay confirmaciones parecidas o si fueron a categorías distintas. */
    @Transactional(readOnly = true)
    public Optional<Aprendido> buscar(String descripcion, Catalogo catalogo) {
        String clave = ClaveDescripcion.de(descripcion);
        if (clave.isEmpty()) {
            return Optional.empty();
        }
        List<DescripcionConfirmada> parecidas = movimientos.descripcionesConfirmadas().stream()
                .filter(d -> ClaveDescripcion.parecidas(clave, ClaveDescripcion.de(d.descripcion())))
                .toList();
        List<Long> categorias = parecidas.stream().map(DescripcionConfirmada::categoriaId).distinct().toList();
        if (categorias.size() != 1) {
            return Optional.empty();
        }
        Long categoriaId = categorias.get(0);
        return catalogo.categorias().stream()
                .filter(c -> categoriaId.equals(c.getId()))
                .findFirst()
                .map(c -> new Aprendido(c, parecidas.get(0).descripcion()));
    }
}
