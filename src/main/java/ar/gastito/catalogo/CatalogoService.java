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

    /** Las tablas son chicas: se leen en cada mensaje, así un cambio en la base se ve enseguida. */
    @Transactional(readOnly = true)
    public Catalogo cargar() {
        return new Catalogo(categorias.findByActivaTrueOrderByNombre(), medios.findByActivoTrueOrderById(),
                cuentas.findByActivaTrueOrderById(), tarjetas.findFirstByActivaTrueOrderById());
    }
}
