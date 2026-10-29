package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.catalogo.TarjetaCreditoRepository;

@Service
public class MovimientoService {

    /** Cuántas confirmaciones recientes se le pasan a la IA como ejemplos. */
    public static final int EJEMPLOS_PARA_LA_IA = 20;

    private final MovimientoRepository movimientos;
    private final CategoriaRepository categorias;
    private final MedioPagoRepository medios;
    private final TarjetaCreditoRepository tarjetas;
    private final CuentaRepository cuentas;
    private final Clock clock;

    public MovimientoService(MovimientoRepository movimientos, CategoriaRepository categorias,
            MedioPagoRepository medios, TarjetaCreditoRepository tarjetas, CuentaRepository cuentas, Clock clock) {
        this.movimientos = movimientos;
        this.categorias = categorias;
        this.medios = medios;
        this.tarjetas = tarjetas;
        this.cuentas = cuentas;
        this.clock = clock;
    }

    public record Confirmacion(Movimiento movimiento, BigDecimal totalMesCategoria) {
    }

    public record ResumenDia(List<Movimiento> gastos, BigDecimal total) {
    }

    public record ResumenMes(YearMonth mes, List<TotalPorCategoria> porCategoria, BigDecimal total) {
    }

    @Transactional
    public Movimiento crearPendiente(MovimientoInterpretado interpretado, Origen origen, String textoOriginal) {
        return movimientos.save(Movimiento.pendiente(interpretado, origen, textoOriginal, LocalDateTime.now(clock)));
    }

    /** Un gasto leído de un ticket, con sus productos. */
    @Transactional
    public Movimiento crearPendienteDeTicket(MovimientoInterpretado interpretado, DatosTicket ticket,
            String textoOriginal) {
        return movimientos.save(Movimiento.deTicket(interpretado, ticket, textoOriginal, LocalDateTime.now(clock)));
    }

    @Transactional
    public void asociarFotoTicket(Long id, String ruta) {
        buscar(id).asociarFotoTicket(ruta);
    }

    @Transactional
    public void quitarFotoTicket(Long id) {
        buscar(id).quitarFotoTicket();
    }

    /**
     * Confirma el movimiento y devuelve un total del mes del movimiento: el de su categoría si es un gasto, o el
     * de todos los ingresos si es un ingreso.
     */
    @Transactional
    public Confirmacion confirmar(Long id) {
        Movimiento m = buscarPendiente(id);
        m.confirmar();
        movimientos.flush();
        Periodo mes = Periodo.delMes(YearMonth.from(m.getFechaHora()));
        BigDecimal total = m.isIngreso()
                ? movimientos.totalIngresos(mes.desde(), mes.hasta())
                : movimientos.totalGastosDeCategoria(m.getCategoria().getId(), mes.desde(), mes.hasta());
        return new Confirmacion(m, total);
    }

    @Transactional
    public Movimiento cancelar(Long id) {
        Movimiento m = buscarPendiente(id);
        m.cancelar();
        return m;
    }

    @Transactional
    public Movimiento elegirCategoria(Long id, Long categoriaId) {
        Movimiento m = buscarPendiente(id);
        m.elegirCategoria(categorias.findById(categoriaId)
                .orElseThrow(() -> new NoSuchElementException("No existe la categoría " + categoriaId)));
        return m;
    }

    @Transactional
    public Movimiento cambiarMonto(Long id, BigDecimal monto) {
        Movimiento m = buscarPendiente(id);
        m.cambiarMonto(monto);
        return m;
    }

    @Transactional
    public Movimiento cambiarDescripcion(Long id, String descripcion) {
        Movimiento m = buscarPendiente(id);
        m.cambiarDescripcion(descripcion);
        return m;
    }

    @Transactional
    public Movimiento cambiarFechaHora(Long id, LocalDateTime fechaHora) {
        Movimiento m = buscarPendiente(id);
        m.cambiarFechaHora(fechaHora);
        return m;
    }

    /** @param medioPagoId null para dejarlo sin medio de pago */
    @Transactional
    public Movimiento cambiarMedioPago(Long id, Long medioPagoId) {
        Movimiento m = buscarPendiente(id);
        MedioPago medio = medioPagoId == null ? null : medios.findById(medioPagoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el medio de pago " + medioPagoId));
        m.cambiarMedioPago(medio, tarjetas.findFirstByActivaTrueOrderById().orElse(null));
        return m;
    }

    /** Lo último que cargó el usuario (texto, audio, ticket o dashboard), si hay algo. */
    @Transactional(readOnly = true)
    public Optional<Movimiento> ultimoCargado() {
        return movimientos.ultimosCargados(PageRequest.of(0, 1)).stream().findFirst().map(m -> buscar(m.getId()));
    }

    /**
     * Un cambio sobre algo ya cargado (pendiente o guardado): solo lo que no es null.
     *
     * @param categoriaId null para no cambiarla
     * @param medioPagoId null para no cambiarlo
     */
    @Transactional
    public Movimiento corregir(Long id, BigDecimal monto, String descripcion, LocalDateTime fechaHora,
            Long categoriaId, Long medioPagoId) {
        Movimiento m = movimientos.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
        Categoria categoria = categoriaId == null ? null : categorias.findById(categoriaId)
                .orElseThrow(() -> new NoSuchElementException("No existe la categoría " + categoriaId));
        MedioPago medio = medioPagoId == null ? null : medios.findById(medioPagoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el medio de pago " + medioPagoId));
        m.corregir(monto, descripcion, fechaHora, categoria, medio,
                tarjetas.findFirstByActivaTrueOrderById().orElse(null));
        return m;
    }

    /**
     * Edición completa de un gasto, pendiente o guardado: reemplaza monto, descripción, fecha, categoría, medio de
     * pago y cuenta.
     *
     * @param medioPagoId null es "sin especificar"
     * @param cuentaId    null es sin cuenta
     */
    @Transactional
    public Movimiento editar(Long id, BigDecimal monto, String descripcion, LocalDateTime fechaHora, Long categoriaId,
            Long medioPagoId, Long cuentaId) {
        Movimiento m = buscar(id);
        Categoria categoria = categoriaId == null ? null : categorias.findById(categoriaId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la categoría " + categoriaId));
        MedioPago medio = medioPagoId == null ? null : medios.findById(medioPagoId)
                .orElseThrow(() -> new IllegalArgumentException("No existe el medio de pago " + medioPagoId));
        Cuenta cuenta = cuentaId == null ? null : cuentas.findById(cuentaId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la cuenta " + cuentaId));
        m.editar(monto, descripcion, fechaHora, categoria, medio, cuenta,
                tarjetas.findFirstByActivaTrueOrderById().orElse(null));
        return m;
    }

    /**
     * Borra un gasto común de verdad (con sus productos de ticket): ↩️ Deshacer de uno que se guardó solo, o
     * Eliminar en el dashboard. La foto del ticket no se toca. Una juntada o una cuota tienen su propio camino.
     */
    @Transactional
    public Movimiento borrar(Long id) {
        Movimiento m = buscar(id);
        if (m.getTipo() != TipoMovimiento.GASTO) {
            throw new MovimientoEspecialException("Solo se borran gastos");
        }
        if (m.isJuntada()) {
            throw new MovimientoEspecialException("Es una juntada: se borra entera, con lo que te deben");
        }
        if (m.getPlanCuotasId() != null) {
            throw new MovimientoEspecialException("Es una cuota: se cancela el plan desde esa cuota");
        }
        movimientos.desvincularUsoIa(id);
        movimientos.delete(m);
        movimientos.flush();
        return m;
    }

    /** "Borrá lo último": queda cancelado (no cuenta, pero queda el registro). */
    @Transactional
    public Movimiento anular(Long id) {
        Movimiento m = movimientos.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
        m.anular();
        return m;
    }

    @Transactional(readOnly = true)
    public List<Movimiento> ultimasConfirmaciones() {
        return movimientos.ultimosConfirmados(PageRequest.of(0, EJEMPLOS_PARA_LA_IA));
    }

    @Transactional(readOnly = true)
    public Movimiento buscar(Long id) {
        Movimiento m = movimientos.findConDetalleById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
        // Los productos de un ticket, en una consulta aparte (en el mismo JOIN que las candidatas se repetirían).
        m.getItems().size();
        return m;
    }

    @Transactional(readOnly = true)
    public ResumenDia hoy() {
        Periodo dia = Periodo.delDia(LocalDate.now(clock));
        return new ResumenDia(movimientos.gastosConfirmados(dia.desde(), dia.hasta()),
                movimientos.totalGastos(dia.desde(), dia.hasta()));
    }

    @Transactional(readOnly = true)
    public ResumenMes mesActual() {
        YearMonth mes = YearMonth.now(clock);
        Periodo periodo = Periodo.delMes(mes);
        return new ResumenMes(mes, movimientos.totalesPorCategoria(periodo.desde(), periodo.hasta()),
                movimientos.totalGastos(periodo.desde(), periodo.hasta()));
    }

    private Movimiento buscarPendiente(Long id) {
        Movimiento m = buscar(id);
        if (!m.isPendiente()) {
            throw new MovimientoNoPendienteException(m);
        }
        return m;
    }
}
