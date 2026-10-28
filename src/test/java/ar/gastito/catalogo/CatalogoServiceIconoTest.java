package ar.gastito.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CatalogoServiceIconoTest {

    private final CategoriaRepository categorias = mock(CategoriaRepository.class);
    private final CatalogoService servicio = new CatalogoService(categorias, mock(MedioPagoRepository.class),
            mock(CuentaRepository.class), mock(TarjetaCreditoRepository.class));

    @Test
    void guardaUnNombreDeLucide() {
        Categoria kiosco = new Categoria(1L, "Kiosco", "kiosco", null, null);
        when(categorias.findById(1L)).thenReturn(Optional.of(kiosco));

        assertThat(servicio.cambiarIcono(1L, "shopping-cart").getIcono()).isEqualTo("shopping-cart");
    }

    @Test
    void rechazaLoQueNoEsUnNombreDeIcono() {
        for (String malo : new String[] { null, "", "Shopping Cart", "a/b", "<svg>", "x".repeat(41), "-a" }) {
            assertThatThrownBy(() -> servicio.cambiarIcono(1L, malo)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unaCategoriaQueNoExisteEsUnError() {
        when(categorias.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarIcono(9L, "pill")).isInstanceOf(NoSuchElementException.class);
    }
}
