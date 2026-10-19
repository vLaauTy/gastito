package ar.gastito.interpretacion;

import static ar.gastito.interpretacion.CatalogoDePrueba.COMIDA;
import static ar.gastito.interpretacion.CatalogoDePrueba.KIOSCO;
import static ar.gastito.interpretacion.CatalogoDePrueba.SUPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.movimiento.DescripcionConfirmada;
import ar.gastito.movimiento.MovimientoRepository;

class HistorialCategoriasTest {

    private final MovimientoRepository repositorio = mock(MovimientoRepository.class);
    private final HistorialCategorias historial = new HistorialCategorias(repositorio);
    private final Catalogo catalogo = CatalogoDePrueba.crear();

    private void confirmados(DescripcionConfirmada... confirmados) {
        when(repositorio.descripcionesConfirmadas()).thenReturn(List.of(confirmados));
    }

    @Test
    void usaLaCategoriaQueYaLeDioElUsuario() {
        confirmados(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()));

        HistorialCategorias.Aprendido a = historial.buscar("en pan de pancho y papas", catalogo).orElseThrow();

        assertThat(a.categoria()).isSameAs(SUPER);
        assertThat(a.descripcion()).isEqualTo("Pan de pancho y papas");
    }

    @Test
    void reconoceDescripcionesMuyParecidas() {
        confirmados(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()));

        assertThat(historial.buscar("papas y pan de pacho", catalogo)).isPresent();
        assertThat(historial.buscar("PAN DE PANCHO Y PAPAS", catalogo)).isPresent();
    }

    @Test
    void siempreEnLaMismaCategoriaAunqueConDistintasDescripciones() {
        confirmados(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()),
                new DescripcionConfirmada("pan de pancho, papas", SUPER.getId()));

        assertThat(historial.buscar("pan de pancho y papas", catalogo).orElseThrow().categoria()).isSameAs(SUPER);
    }

    @Test
    void siFueronACategoriasDistintasNoDecide() {
        confirmados(new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()),
                new DescripcionConfirmada("Papas y pan de pancho", KIOSCO.getId()));

        assertThat(historial.buscar("pan de pancho y papas", catalogo)).isEmpty();
    }

    @Test
    void ignoraLasQueNoSeParecen() {
        confirmados(new DescripcionConfirmada("Pizza", COMIDA.getId()),
                new DescripcionConfirmada("Pan de pancho y papas", SUPER.getId()));

        assertThat(historial.buscar("pan de pancho y papas", catalogo).orElseThrow().categoria()).isSameAs(SUPER);
        assertThat(historial.buscar("hamburguesa", catalogo)).isEmpty();
    }

    @Test
    void sinHistorialOSinDescripcionNoHayNada() {
        confirmados();
        assertThat(historial.buscar("pan", catalogo)).isEmpty();
        assertThat(historial.buscar("  ", catalogo)).isEmpty();
        assertThat(historial.buscar(null, catalogo)).isEmpty();
    }
}
