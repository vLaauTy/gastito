package ar.gastito.dolar;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.interpretacion.ClaveDescripcion;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoEspecialException;
import ar.gastito.movimiento.MovimientoRepository;

/**
 * Compras en dólares con la tarjeta de crédito: en el momento se sabe cuántos dólares, pero no cuántos pesos (eso
 * lo dice el resumen). Java estima los pesos con la cotización de referencia que carga el usuario y los marca como
 * estimados; sin cotización, la compra queda en 0 pesos ("≈ sin estimar"). Cuando llega el resumen, se corrige con
 * lo que cobraron y queda la cotización real. Los totales siempre suman pesos ({@code movimiento.monto}).
 */
@Service
public class CompraEnDolaresService {

    private final CotizacionDolarRepository cotizaciones;
    private final MovimientoRepository movimientos;
    private final CatalogoService catalogos;
    private final Clock clock;

    public CompraEnDolaresService(CotizacionDolarRepository cotizaciones, MovimientoRepository movimientos,
            CatalogoService catalogos, Clock clock) {
        this.cotizaciones = cotizaciones;
        this.movimientos = movimientos;
        this.catalogos = catalogos;
        this.clock = clock;
    }

    /** La cotización de referencia (la última cargada), si hay. */
    @Transactional(readOnly = true)
    public Optional<CotizacionDolar> referencia() {
        return cotizaciones.findFirstByOrderByCargadaEnDescIdDesc();
    }

    /**
     * Lo que cambió al cargar una cotización.
     *
     * @param estimadas las compras que estaban sin estimar y ahora tienen los pesos estimados
     */
    public record CotizacionCargada(CotizacionDolar cotizacion, List<Movimiento> estimadas) {
    }

    /**
     * "/dolar 1500", "el dólar tarjeta está a 1500" o desde la web. Las compras que quedaron sin estimar se estiman
     * con esta; las ya estimadas siguen con la cotización de su momento.
     */
    @Transactional
    public CotizacionCargada cargarCotizacion(BigDecimal valor) {
        CotizacionDolar cotizacion = cotizaciones.save(new CotizacionDolar(valor, LocalDateTime.now(clock)));
        List<Movimiento> estimadas = movimientos.comprasEnDolaresEstimadas().stream()
                .filter(Movimiento::isSinEstimar).toList();
        estimadas.forEach(m -> m.estimarPesos(cotizacion.getValor()));
        return new CotizacionCargada(cotizacion, estimadas);
    }

    /**
     * El gasto pendiente es una compra en dólares: sin medio de pago, va con la tarjeta de crédito (las compras en
     * dólares solo se registran así); con otro medio, no se cambia (el bot pregunta antes).
     *
     * @throws MovimientoEspecialException si se pagó con otro medio
     */
    @Transactional
    public Movimiento pasarADolares(Long id, BigDecimal dolares) {
        Movimiento m = buscar(id);
        if (m.getMedioPago() == null) {
            Catalogo catalogo = catalogos.cargar();
            MedioPago credito = catalogo.mediosPago().stream().filter(MedioPago::esTarjetaDeCredito).findFirst()
                    .orElseThrow(() -> new MovimientoEspecialException("No hay una tarjeta de crédito cargada"));
            m.cambiarMedioPago(credito, catalogo.tarjetaCredito().orElse(null));
        }
        m.pasarADolares(dolares, referencia().map(CotizacionDolar::getValor).orElse(null));
        return m;
    }

    /** "Lo de Steam salió 31.200" o Corregir monto en la web: los pesos reales, según el resumen. */
    @Transactional
    public Movimiento corregirPesos(Long id, BigDecimal pesos) {
        Movimiento m = buscar(id);
        m.corregirPesosDeDolares(pesos);
        return m;
    }

    /**
     * La compra en dólares guardada y todavía estimada que corresponde a un gasto nuevo en pesos con la misma
     * descripción ("lo de Steam salió 31.200" → la compra de Steam), la más nueva. Vacío si el nuevo no es un gasto
     * pendiente en pesos o no hay ninguna parecida.
     */
    @Transactional(readOnly = true)
    public Optional<Movimiento> estimadaParecida(Movimiento nuevo) {
        if (!nuevo.isPendiente() || nuevo.isEnDolares() || nuevo.getMonto() == null
                || nuevo.isJuntada() || nuevo.getPlanCuotasId() != null || nuevo.getGastoFijoId() != null
                || (nuevo.getMedioPago() != null && !nuevo.getMedioPago().esTarjetaDeCredito())) {
            return Optional.empty();
        }
        String clave = ClaveDescripcion.de(nuevo.getDescripcion());
        if (clave.isEmpty()) {
            return Optional.empty();
        }
        return movimientos.comprasEnDolaresEstimadas().stream()
                .filter(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO)
                .filter(m -> clave.equals(ClaveDescripcion.de(m.getDescripcion())))
                .findFirst();
    }

    /** Lo que quedó al corregir una compra en dólares con lo que se acaba de decir. */
    public record Correccion(Movimiento compra, BigDecimal anterior) {
    }

    /**
     * "Actualizar el monto": el gasto que se acaba de interpretar ("lo de Steam salió 31.200") era lo que cobraron
     * por la compra en dólares. La compra pasa a tener esos pesos y el nuevo se cancela.
     */
    @Transactional
    public Correccion corregirCon(Long pendienteId, Long compraId) {
        Movimiento nuevo = buscar(pendienteId);
        if (!nuevo.isPendiente() || nuevo.getMonto() == null) {
            throw new IllegalStateException("Ese gasto ya no está pendiente");
        }
        Movimiento compra = buscar(compraId);
        BigDecimal anterior = compra.getMonto();
        compra.corregirPesosDeDolares(nuevo.getMonto());
        nuevo.cancelar();
        return new Correccion(compra, anterior);
    }

    private Movimiento buscar(Long id) {
        return movimientos.findConDetalleById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
    }
}
