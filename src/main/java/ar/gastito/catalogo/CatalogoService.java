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

    // --- Tarjetas de crédito (el dashboard las agrega, edita y da de baja; el historial no se borra) ---

    /** Los datos de una tarjeta como se cargan en el dashboard. @param alias separados por coma */
    public record DatosTarjeta(String nombre, String banco, Long cuentaDebitoId, String color, String inicial,
            String alias) {
    }

    /** Todas, las activas primero. */
    @Transactional(readOnly = true)
    public java.util.List<TarjetaCredito> todasLasTarjetas() {
        java.util.List<TarjetaCredito> todas = tarjetas.findAllByOrderByActivaDescIdAsc();
        todas.forEach(t -> {
            if (t.getCuentaDebito() != null) {
                t.getCuentaDebito().getNombre();
            }
        });
        return todas;
    }

    @Transactional
    public TarjetaCredito altaTarjeta(DatosTarjeta d) {
        if (d.nombre() != null && tarjetas.existsByNombreIgnoreCase(d.nombre().strip())) {
            throw new IllegalArgumentException("Ya hay una tarjeta que se llama " + d.nombre().strip());
        }
        return tarjetas.save(new TarjetaCredito(d.nombre(), d.banco(), cuenta(d.cuentaDebitoId()), d.color(),
                d.inicial(), d.alias()));
    }

    @Transactional
    public TarjetaCredito editarTarjeta(long id, DatosTarjeta d) {
        TarjetaCredito t = tarjeta(id);
        if (d.nombre() != null && !d.nombre().strip().equalsIgnoreCase(t.getNombre())
                && tarjetas.existsByNombreIgnoreCase(d.nombre().strip())) {
            throw new IllegalArgumentException("Ya hay una tarjeta que se llama " + d.nombre().strip());
        }
        t.editar(d.nombre(), d.banco(), cuenta(d.cuentaDebitoId()), d.color(), d.inicial(), d.alias());
        return t;
    }

    /** Ya no se usa: deja de aparecer para elegir y en el ciclo, pero sus gastos y resúmenes quedan. */
    @Transactional
    public TarjetaCredito bajaTarjeta(long id) {
        TarjetaCredito t = tarjeta(id);
        t.darDeBaja();
        return t;
    }

    @Transactional
    public TarjetaCredito reactivarTarjeta(long id) {
        TarjetaCredito t = tarjeta(id);
        t.reactivar();
        return t;
    }

    private TarjetaCredito tarjeta(long id) {
        return tarjetas.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("No existe la tarjeta " + id));
    }

    private Cuenta cuenta(Long id) {
        return id == null ? null : cuentas.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe la cuenta " + id));
    }

    /** Las tablas son chicas: se leen en cada mensaje, así un cambio en la base se ve enseguida. */
    @Transactional(readOnly = true)
    public Catalogo cargar() {
        return new Catalogo(categorias.findByActivaTrueOrderByNombre(), medios.findByActivoTrueOrderById(),
                cuentas.findByActivaTrueAndTipoOrderById(TipoCuenta.BANCARIA), tarjetas.activasPorUso());
    }
}
