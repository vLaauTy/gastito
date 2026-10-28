package ar.gastito.cuota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * Compras en cuotas: la cuota 1 es la que interpretó la IA (sigue el flujo normal de confirmar/editar/cancelar); al
 * guardarla se generan, ya confirmadas, las que siguen. Cada cuota cuenta como gasto solo en su mes.
 */
@Service
public class PlanCuotasService {

    private final PlanCuotasRepository planes;
    private final MovimientoRepository movimientos;
    private final MovimientoService movimientoService;
    private final CuotaProperties properties;
    private final Clock clock;

    public PlanCuotasService(PlanCuotasRepository planes, MovimientoRepository movimientos,
            MovimientoService movimientoService, CuotaProperties properties, Clock clock) {
        this.planes = planes;
        this.movimientos = movimientos;
        this.movimientoService = movimientoService;
        this.properties = properties;
        this.clock = clock;
    }

    // --- Crear y guardar ---

    /** El gasto recién creado es la cuota 1 de un plan: su monto es el total, que queda en el valor de cada cuota. */
    @Transactional
    public Movimiento crear(Long movimientoId, int cantidad, BigDecimal total) {
        Movimiento m = movimiento(movimientoId);
        if (m.getMonto() == null) {
            throw new IllegalStateException("Falta el monto para armar el plan de cuotas");
        }
        YearMonth primerMes = YearMonth.from(m.getFechaHora());
        if (properties.primeraCuota() == CuotaProperties.PrimeraCuota.MES_SIGUIENTE) {
            primerMes = primerMes.plusMonths(1);
        }
        BigDecimal valorCuota = total.divide(BigDecimal.valueOf(cantidad), 2, RoundingMode.HALF_UP);
        PlanCuotas plan = planes.save(new PlanCuotas(m.getDescripcion(), total, cantidad, valorCuota,
                m.getFechaHora(), primerMes, m.getCategoria(), m.getTarjeta(), LocalDateTime.now(clock)));
        m.cambiarMonto(valorCuota);
        m.prepararCuota(plan.getId(), 1, cantidad);
        return m;
    }

    /**
     * Se guardó la cuota 1: el plan toma lo que quedó confirmado (puede haber cambiado la categoría, la tarjeta o el
     * monto antes de guardar) y se generan, ya confirmadas, las cuotas que siguen.
     */
    @Transactional
    public List<Movimiento> alGuardar(Long movimientoId) {
        Movimiento primera = movimiento(movimientoId);
        PlanCuotas plan = plan(primera.getPlanCuotasId());
        plan.actualizarValores(primera.getMonto(), primera.getCategoria(), primera.getTarjeta());
        List<Movimiento> nuevas = new ArrayList<>();
        for (int numero = 2; numero <= plan.getCantidad(); numero++) {
            Movimiento cuota = movimientoService.crearPendiente(MovimientoInterpretado.conCategoria(
                    plan.getValorCuota(), plan.getDescripcion(), plan.getCategoria(), null, null, plan.getTarjeta(),
                    plan.fechaDeCuota(numero)), Origen.AUTOMATICO, null);
            cuota.prepararCuota(plan.getId(), numero, plan.getCantidad());
            cuota.confirmar();
            nuevas.add(cuota);
        }
        return nuevas;
    }

    // --- Cancelar ---

    /** "Si lo pagué entero antes": se borran las cuotas con fecha futura; las que ya pasaron quedan. */
    @Transactional
    public VistaPlanCuotas cancelar(Long planId) {
        PlanCuotas plan = plan(planId);
        plan.cancelar();
        movimientos.borrarCuotasFuturas(planId, LocalDate.now(clock).plusDays(1).atStartOfDay());
        return vista(plan);
    }

    // --- Consultas ---

    /** Los planes activos que ya tienen al menos una cuota guardada (si se canceló el gasto, no cuenta). */
    @Transactional(readOnly = true)
    public List<VistaPlanCuotas> activos() {
        return planes.findByEstadoOrderByIdDesc(EstadoPlanCuotas.ACTIVO).stream()
                .filter(p -> !movimientos.findByPlanCuotasIdOrderByCuotaActual(p.getId()).isEmpty())
                .map(this::vista)
                .toList();
    }

    /** Lo que suman las cuotas que todavía no ocurrieron de los planes activos, mes a mes (desde el mes actual). */
    public record MesComprometido(YearMonth mes, BigDecimal total, int cuotas, int porcentajeDelMaximo) {
    }

    @Transactional(readOnly = true)
    public List<MesComprometido> comprometido(int meses) {
        LocalDate hoy = LocalDate.now(clock);
        YearMonth desde = YearMonth.from(hoy);
        BigDecimal[] totales = new BigDecimal[meses];
        int[] cantidades = new int[meses];
        java.util.Arrays.fill(totales, BigDecimal.ZERO);
        for (PlanCuotas plan : planes.findByEstadoOrderByIdDesc(EstadoPlanCuotas.ACTIVO)) {
            for (Movimiento cuota : movimientos.findByPlanCuotasIdOrderByCuotaActual(plan.getId())) {
                if (!cuota.getFechaHora().toLocalDate().isAfter(hoy)) {
                    continue;
                }
                int i = (int) java.time.temporal.ChronoUnit.MONTHS.between(desde, YearMonth.from(cuota.getFechaHora()));
                if (i >= 0 && i < meses) {
                    totales[i] = totales[i].add(cuota.getMonto());
                    cantidades[i]++;
                }
            }
        }
        BigDecimal maximo = java.util.Arrays.stream(totales).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        List<MesComprometido> lista = new ArrayList<>();
        for (int i = 0; i < meses; i++) {
            int porcentaje = maximo.signum() == 0 ? 0
                    : totales[i].multiply(BigDecimal.valueOf(100)).divide(maximo, 0, RoundingMode.HALF_UP).intValue();
            lista.add(new MesComprometido(desde.plusMonths(i), totales[i], cantidades[i], porcentaje));
        }
        return lista;
    }

    @Transactional(readOnly = true)
    public VistaPlanCuotas vista(Long planId) {
        return vista(plan(planId));
    }

    private VistaPlanCuotas vista(PlanCuotas plan) {
        LocalDate hoy = LocalDate.now(clock);
        List<Movimiento> cuotas = movimientos.findByPlanCuotasIdOrderByCuotaActual(plan.getId());
        int pagadas = (int) cuotas.stream().filter(c -> !c.getFechaHora().toLocalDate().isAfter(hoy)).count();
        Optional<Movimiento> proxima = cuotas.stream().filter(c -> c.getFechaHora().toLocalDate().isAfter(hoy))
                .findFirst();
        return new VistaPlanCuotas(plan.getId(), plan.getDescripcion(), plan.getTotal(), plan.getCantidad(),
                plan.getValorCuota(), plan.getCategoria() == null ? null : plan.getCategoria().getNombre(),
                plan.getCategoria() == null ? null : plan.getCategoria().getEmoji(),
                plan.getTarjeta() == null ? null : plan.getTarjeta().getNombre(), pagadas,
                plan.getCantidad() - pagadas, proxima.map(c -> c.getFechaHora().toLocalDate()).orElse(null),
                plan.getEstado() == EstadoPlanCuotas.ACTIVO);
    }

    private PlanCuotas plan(Long id) {
        return planes.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el plan " + id));
    }

    private Movimiento movimiento(Long id) {
        return movimientos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
    }
}
