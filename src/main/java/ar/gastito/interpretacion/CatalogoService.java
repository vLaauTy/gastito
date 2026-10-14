package ar.gastito.interpretacion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.CategoriaRepository;
import ar.gastito.movimiento.CuentaRepository;
import ar.gastito.movimiento.MedioPagoRepository;
import ar.gastito.movimiento.TarjetaRepository;

@Service
public class CatalogoService {

    private final CategoriaRepository categorias;
    private final MedioPagoRepository medios;
    private final CuentaRepository cuentas;
    private final TarjetaRepository tarjetas;

    public CatalogoService(CategoriaRepository categorias, MedioPagoRepository medios, CuentaRepository cuentas,
            TarjetaRepository tarjetas) {
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
