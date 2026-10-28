package ar.gastito.catalogo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogoService {

    private final CategoriaRepository categorias;
    private final MedioPagoRepository medios;
    private final CuentaRepository cuentas;
    private final TarjetaCreditoRepository tarjetas;

    public CatalogoService(CategoriaRepository categorias, MedioPagoRepository medios, CuentaRepository cuentas,
            TarjetaCreditoRepository tarjetas) {
        this.categorias = categorias;
        this.medios = medios;
        this.cuentas = cuentas;
        this.tarjetas = tarjetas;
    }

    /** Cambia el ícono de una categoría (un nombre de Lucide: minúsculas, números y guiones). */
    @Transactional
    public Categoria cambiarIcono(long id, String icono) {
        if (icono == null || !icono.matches("[a-z0-9]+(-[a-z0-9]+)*") || icono.length() > 40) {
            throw new IllegalArgumentException("El ícono tiene que ser un nombre de Lucide, por ejemplo shopping-cart.");
        }
        Categoria categoria = categorias.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("No existe esa categoría."));
        categoria.setIcono(icono);
        return categoria;
    }

    /** Las tablas son chicas: se leen en cada mensaje, así un cambio en la base se ve enseguida. */
    @Transactional(readOnly = true)
    public Catalogo cargar() {
        return new Catalogo(categorias.findByActivaTrueOrderByNombre(), medios.findByActivoTrueOrderById(),
                cuentas.findByActivaTrueOrderById(), tarjetas.findFirstByActivaTrueOrderById());
    }
}
