package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;

/**
 * Ingresos: sueldo, aguinaldo y extras. Se guardan como movimientos de tipo INGRESO.
 * Desde el dashboard se cargan ya confirmados; desde el bot quedan pendientes hasta que el usuario los guarda.
 */
@Service
public class IngresoService {

    static final int LARGO_MAXIMO_DESCRIPCION = 255;

    private final MovimientoRepository movimientos;
    private final CuentaRepository cuentas;
    private final MedioPagoRepository medios;
    private final Clock clock;

    public IngresoService(MovimientoRepository movimientos, CuentaRepository cuentas, MedioPagoRepository medios,
            Clock clock) {
        this.movimientos = movimientos;
        this.cuentas = cuentas;
        this.medios = medios;
        this.clock = clock;
    }

    /**
     * @param ingresos  los del mes, del más viejo al más nuevo
     * @param cantidadExtras cuántos ingresos extra hubo (para "1 trabajo")
     */
    public record ResumenIngresos(YearMonth mes, List<Movimiento> ingresos, BigDecimal total, BigDecimal sueldo,
            BigDecimal aguinaldo, BigDecimal extras, int cantidadExtras) {
    }

    /**
     * Un ingreso cargado desde el dashboard: queda guardado directamente, porque lo escribió el usuario.
     *
     * @param cuentaId la cuenta donde entró, o null si fue en efectivo
     * @throws IllegalArgumentException si el monto, la fecha o la cuenta no sirven (el mensaje es para el usuario)
     */
    @Transactional
    public Movimiento cargar(ClaseIngreso clase, BigDecimal monto, String descripcion, Long cuentaId, LocalDate fecha) {
        if (clase == null) {
            throw new IllegalArgumentException("Elegí el tipo de ingreso");
        }
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        LocalDate hoy = LocalDate.now(clock);
        if (fecha == null || fecha.isAfter(hoy)) {
            throw new IllegalArgumentException("La fecha no puede ser futura");
        }
        Cuenta cuenta = cuentaId == null ? null : cuentas.findById(cuentaId)
                .orElseThrow(() -> new IllegalArgumentException("No existe esa cuenta"));
        Movimiento m = Movimiento.ingreso(clase, monto, limpiar(descripcion), cuenta, efectivo(cuenta == null),
                fecha.atStartOfDay(), Origen.DASHBOARD, null, ahora());
        m.confirmar();
        return movimientos.save(m);
    }

    /**
     * Un ingreso dicho en el bot (/sueldo, /aguinaldo, /ingreso): queda pendiente hasta que el usuario lo guarda.
     * Si no dice dónde entró, va a la misma cuenta (o en efectivo) que el último ingreso guardado de esa clase.
     */
    @Transactional
    public Movimiento crearPendiente(ClaseIngreso clase, BigDecimal monto, String descripcion, Cuenta cuenta,
            boolean enEfectivo, LocalDateTime fechaHora, String textoOriginal) {
        return crearPendiente(clase, monto, descripcion, cuenta, enEfectivo, fechaHora, Origen.TEXTO, textoOriginal);
    }

    private Movimiento crearPendiente(ClaseIngreso clase, BigDecimal monto, String descripcion, Cuenta cuenta,
            boolean enEfectivo, LocalDateTime fechaHora, Origen origen, String textoOriginal) {
        if (cuenta == null && !enEfectivo) {
            List<Movimiento> anterior = movimientos.ultimosIngresos(clase, PageRequest.of(0, 1));
            if (!anterior.isEmpty()) {
                cuenta = anterior.get(0).getCuenta();
                enEfectivo = cuenta == null && anterior.get(0).getMedioPago() != null
                        && anterior.get(0).getMedioPago().isEfectivo();
            }
        }
        return movimientos.save(Movimiento.ingreso(clase, monto, limpiar(descripcion), cuenta, efectivo(enEfectivo),
                fechaHora, origen, textoOriginal, ahora()));
    }

    /** Borra un ingreso guardado (queda cancelado). */
    @Transactional
    public Movimiento anular(Long id) {
        Movimiento m = movimientos.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
        m.anularIngreso();
        return m;
    }

    @Transactional(readOnly = true)
    public ResumenIngresos delMes(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        List<Movimiento> ingresos = movimientos.ingresosConfirmados(periodo.desde(), periodo.hasta());
        Map<ClaseIngreso, BigDecimal> porClase = ingresos.stream().collect(Collectors.groupingBy(
                Movimiento::getClaseIngreso, Collectors.reducing(BigDecimal.ZERO, Movimiento::getMonto, BigDecimal::add)));
        int extras = (int) ingresos.stream().filter(m -> m.getClaseIngreso() == ClaseIngreso.EXTRA).count();
        return new ResumenIngresos(mes, ingresos,
                porClase.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                porClase.getOrDefault(ClaseIngreso.SUELDO, BigDecimal.ZERO),
                porClase.getOrDefault(ClaseIngreso.AGUINALDO, BigDecimal.ZERO),
                porClase.getOrDefault(ClaseIngreso.EXTRA, BigDecimal.ZERO),
                extras);
    }

    @Transactional(readOnly = true)
    public BigDecimal totalDelMes(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        return movimientos.totalIngresos(periodo.desde(), periodo.hasta());
    }

    /** El total de cada uno de los últimos meses hasta {@code hasta} inclusive, del más viejo al más nuevo, con ceros. */
    @Transactional(readOnly = true)
    public List<TotalPorMes> porMes(YearMonth hasta, int meses) {
        if (meses < 1) {
            throw new IllegalArgumentException("Hace falta al menos un mes");
        }
        YearMonth desde = hasta.minusMonths(meses - 1L);
        Map<YearMonth, BigDecimal> totales = movimientos
                .ingresosPorMes(desde.atDay(1).atStartOfDay(), hasta.plusMonths(1).atDay(1).atStartOfDay())
                .stream().collect(Collectors.toMap(TotalPorMes::yearMonth, TotalPorMes::total));
        List<TotalPorMes> resultado = new ArrayList<>();
        for (YearMonth mes = desde; !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
            resultado.add(new TotalPorMes(mes.getYear(), mes.getMonthValue(),
                    totales.getOrDefault(mes, BigDecimal.ZERO)));
        }
        return resultado;
    }

    private MedioPago efectivo(boolean enEfectivo) {
        if (!enEfectivo) {
            return null;
        }
        return medios.findFirstByEfectivoTrueOrderById()
                .orElseThrow(() -> new IllegalStateException("No hay un medio de pago marcado como efectivo"));
    }

    private static String limpiar(String descripcion) {
        if (descripcion == null || descripcion.isBlank()) {
            return null;
        }
        String d = descripcion.strip();
        d = d.substring(0, 1).toUpperCase() + d.substring(1);
        return d.length() > LARGO_MAXIMO_DESCRIPCION ? d.substring(0, LARGO_MAXIMO_DESCRIPCION) : d;
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }
}
