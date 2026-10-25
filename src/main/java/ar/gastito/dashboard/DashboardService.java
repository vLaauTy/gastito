package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.ia.ResumenUsoIa;
import ar.gastito.ia.UsoIaRepository;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.InicioRegistro;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Periodo;
import ar.gastito.movimiento.TotalPorMes;

/**
 * Todos los números del dashboard. Se calculan acá (y en SQL), nunca en el navegador: el frontend solo los muestra,
 * y la misma API va a servir para la Mini App de Telegram.
 * <p>
 * Plata en pesos con 2 decimales; porcentajes con 1 decimal (los de variación, enteros). Meses en hora argentina.
 */
@Service
public class DashboardService {

    /** Colores de las billeteras que no son una cuenta ni una tarjeta. "Sin especificar" va en gris claro. */
    static final String COLOR_EFECTIVO = "#8A968E";
    static final String COLOR_SIN_ESPECIFICAR = "#D5DBD2";
    /** Para las cuentas y tarjetas sin color propio en la base. */
    static final List<String> COLORES_DE_RESERVA = List.of("#7A5AA6", "#2E8C8C", "#B08A1E", "#A3476B", "#4F6D8F");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final DashboardRepository consultas;
    private final IngresoService ingresos;
    private final UsoIaRepository usoIa;
    private final InicioRegistro inicio;
    private final Clock clock;

    public DashboardService(DashboardRepository consultas, IngresoService ingresos, UsoIaRepository usoIa,
            InicioRegistro inicio, Clock clock) {
        this.consultas = consultas;
        this.inicio = inicio;
        this.ingresos = ingresos;
        this.usoIa = usoIa;
        this.clock = clock;
    }

    // --- Respuestas ---

    public enum Direccion {
        SUBE, BAJA, IGUAL, SIN_DATOS
    }

    /**
     * Contra el mes anterior. En el mes actual, hasta el mismo día (o hasta el último día del anterior, si es más
     * corto); en un mes que ya terminó, los dos meses completos.
     *
     * @param variacionPorcentaje entero, o null si el mes anterior no tuvo gastos o no tiene datos
     * @param conDatos            false si el registro empezó después del 1 del mes anterior: no hay con qué comparar
     *                            (no es que se gastó $0). La dirección es SIN_DATOS.
     */
    public record Comparacion(YearMonth mes, BigDecimal total, int hastaDia, boolean mesCompleto,
            Integer variacionPorcentaje, Direccion direccion, boolean conDatos) {
    }

    public record Ingresos(BigDecimal total, BigDecimal sueldo, BigDecimal aguinaldo, BigDecimal extras,
            int cantidadExtras) {
    }

    /** Lo cobrado menos lo gastado. Puede ser negativo. */
    public record Disponible(BigDecimal monto, int porcentajeGastado) {
    }

    /**
     * @param diasContados los días que ya pasaron del mes (hoy incluido); un mes futuro tiene 0
     * @param disponible   null si el mes no tiene ingresos
     */
    public record Resumen(YearMonth mes, boolean mesActual, int diasContados, BigDecimal gastado, long cantidadGastos,
            BigDecimal promedioDiario, Comparacion comparacion, Ingresos ingresos, Disponible disponible) {
    }

    /** @param futuro un día que todavía no llegó (en el mes actual o en uno futuro) */
    public record Dia(int dia, LocalDate fecha, BigDecimal total, boolean futuro) {
    }

    /** @param diaMaximo el día que más se gastó, o null si no hubo gastos */
    public record PorDia(YearMonth mes, List<Dia> dias, BigDecimal promedio, Integer diaMaximo) {
    }

    /** @param porcentajeDelMaximo el ancho de la barra: la categoría más alta es 100 */
    public record CategoriaDelMes(Long id, String nombre, String emoji, BigDecimal total, long cantidad,
            BigDecimal porcentajeDelMaximo, BigDecimal porcentajeDelTotal) {
    }

    public enum TipoBilletera {
        EFECTIVO, CUENTA, TARJETA, SIN_ESPECIFICAR
    }

    /** @param porcentaje parte del total gastado en el mes */
    public record Billetera(String clave, TipoBilletera tipo, String nombre, String color, BigDecimal total,
            long cantidad, BigDecimal porcentaje) {
    }

    public record IngresoDelMes(Long id, LocalDate fecha, String clase, String claseNombre, String descripcion,
            String billetera, BigDecimal monto) {
    }

    public record IngresosDelMes(YearMonth mes, Ingresos totales, List<IngresoDelMes> ingresos) {
    }

    /** @param porcentajeDelMaximo el ancho de la barra: el mes más alto es 100 */
    public record IngresoMensual(YearMonth mes, BigDecimal total, BigDecimal porcentajeDelMaximo) {
    }

    public record CostoPorProposito(String proposito, long llamadas, BigDecimal costoUsd) {
    }

    /** Costos en dólares con 4 decimales: una llamada a Haiku cuesta fracciones de centavo. */
    public record CostosIa(YearMonth mes, long llamadas, long tokensEntrada, long tokensSalida, BigDecimal costoUsd,
            List<CostoPorProposito> porProposito) {
    }

    // --- Cálculos ---

    @Transactional(readOnly = true)
    public Resumen resumen(YearMonth mes) {
        Corte corte = corte(mes);
        Periodo periodo = Periodo.delMes(mes);
        BigDecimal gastado = consultas.totalGastos(periodo.desde(), periodo.hasta());
        long cantidad = consultas.cantidadGastos(periodo.desde(), periodo.hasta());

        IngresoService.ResumenIngresos cobrado = ingresos.delMes(mes);
        Ingresos totalesIngresos = ingresos(cobrado);
        Disponible disponible = cobrado.total().signum() > 0
                ? new Disponible(pesos(cobrado.total().subtract(gastado)),
                        gastado.multiply(CIEN).divide(cobrado.total(), 0, RoundingMode.HALF_UP).intValue())
                : null;

        return new Resumen(mes, corte.mesActual(), corte.dias(), pesos(gastado), cantidad,
                promedio(gastado, corte.dias()), comparacion(mes, corte), totalesIngresos, disponible);
    }

    @Transactional(readOnly = true)
    public PorDia porDia(YearMonth mes) {
        Corte corte = corte(mes);
        Periodo periodo = Periodo.delMes(mes);
        Map<Integer, BigDecimal> totales = consultas.totalesPorDia(periodo.desde(), periodo.hasta()).stream()
                .collect(Collectors.toMap(DashboardRepository.TotalPorDia::dia, DashboardRepository.TotalPorDia::total));

        List<Dia> dias = new ArrayList<>();
        BigDecimal gastado = BigDecimal.ZERO;
        Integer maximo = null;
        for (int d = 1; d <= mes.lengthOfMonth(); d++) {
            BigDecimal total = totales.getOrDefault(d, BigDecimal.ZERO);
            gastado = gastado.add(total);
            if (total.signum() > 0 && (maximo == null || total.compareTo(totales.get(maximo)) > 0)) {
                maximo = d;
            }
            dias.add(new Dia(d, mes.atDay(d), pesos(total), d > corte.dias()));
        }
        return new PorDia(mes, dias, promedio(gastado, corte.dias()), maximo);
    }

    @Transactional(readOnly = true)
    public List<CategoriaDelMes> porCategoria(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        List<DashboardRepository.TotalCategoria> filas = consultas.totalesPorCategoria(periodo.desde(), periodo.hasta());
        BigDecimal maximo = filas.stream().map(DashboardRepository.TotalCategoria::total)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal total = filas.stream().map(DashboardRepository.TotalCategoria::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return filas.stream()
                .map(f -> new CategoriaDelMes(f.categoriaId(), f.nombre(), f.emoji(), pesos(f.total()), f.cantidad(),
                        porcentaje(f.total(), maximo), porcentaje(f.total(), total)))
                .toList();
    }

    /**
     * Agrupa los gastos por la tarjeta o la cuenta de cada uno (así una cuenta o tarjeta nueva aparece sola), o
     * como Efectivo si se pagaron con el medio de pago efectivo. Lo que no tiene nada de eso queda en
     * "Sin especificar", para darse cuenta de los gastos mal cargados. Orden: efectivo, cuentas, tarjetas y sin
     * especificar; dentro de cada tipo, de mayor a menor.
     */
    @Transactional(readOnly = true)
    public List<Billetera> porBilletera(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        Map<String, Acumulado> grupos = new LinkedHashMap<>();
        for (DashboardRepository.FilaBilletera f : consultas.filasBilletera(periodo.desde(), periodo.hasta())) {
            Acumulado grupo;
            if (f.tarjetaId() != null) {
                grupo = grupos.computeIfAbsent("tarjeta-" + f.tarjetaId(), k -> new Acumulado(k,
                        TipoBilletera.TARJETA, f.tarjeta(), color(f.colorTarjeta(), f.tarjetaId())));
            } else if (f.cuentaId() != null) {
                grupo = grupos.computeIfAbsent("cuenta-" + f.cuentaId(), k -> new Acumulado(k,
                        TipoBilletera.CUENTA, f.cuenta(), color(f.colorCuenta(), f.cuentaId())));
            } else if (Boolean.TRUE.equals(f.efectivo())) {
                grupo = grupos.computeIfAbsent("efectivo", k -> new Acumulado(k,
                        TipoBilletera.EFECTIVO, "Efectivo", COLOR_EFECTIVO));
            } else {
                grupo = grupos.computeIfAbsent("sin-especificar", k -> new Acumulado(k,
                        TipoBilletera.SIN_ESPECIFICAR, "Sin especificar", COLOR_SIN_ESPECIFICAR));
            }
            grupo.total = grupo.total.add(f.total());
            grupo.cantidad += f.cantidad();
        }
        BigDecimal total = grupos.values().stream().map(a -> a.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return grupos.values().stream()
                .sorted(Comparator.comparing((Acumulado a) -> a.tipo)
                        .thenComparing((Acumulado a) -> a.total, Comparator.reverseOrder())
                        .thenComparing(a -> a.nombre))
                .map(a -> new Billetera(a.clave, a.tipo, a.nombre, a.color, pesos(a.total), a.cantidad,
                        porcentaje(a.total, total)))
                .toList();
    }

    @Transactional(readOnly = true)
    public IngresosDelMes ingresosDelMes(YearMonth mes) {
        IngresoService.ResumenIngresos r = ingresos.delMes(mes);
        List<IngresoDelMes> filas = r.ingresos().stream().map(DashboardService::ingreso).toList();
        return new IngresosDelMes(mes, ingresos(r), filas);
    }

    @Transactional(readOnly = true)
    public List<IngresoMensual> ingresosPorMes(YearMonth hasta, int meses) {
        List<TotalPorMes> totales = ingresos.porMes(hasta, meses);
        BigDecimal maximo = totales.stream().map(TotalPorMes::total).max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        return totales.stream()
                .map(t -> new IngresoMensual(t.yearMonth(), pesos(t.total()), porcentaje(t.total(), maximo)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CostosIa costosIa(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        ResumenUsoIa r = usoIa.resumen(periodo.desde(), periodo.hasta());
        List<CostoPorProposito> porProposito = usoIa.porProposito(periodo.desde(), periodo.hasta()).stream()
                .map(p -> new CostoPorProposito(p.proposito().name(), p.llamadas(), dolares(p.costoUsd())))
                .toList();
        return new CostosIa(mes, r.llamadas(), r.tokensEntrada(), r.tokensSalida(), dolares(r.costoUsd()),
                porProposito);
    }

    // --- Ayudas ---

    /** Cuántos días del mes ya pasaron (hoy incluido) y si es el mes actual. */
    private record Corte(boolean mesActual, boolean futuro, int dias) {
    }

    private Corte corte(YearMonth mes) {
        LocalDate hoy = LocalDate.now(clock);
        YearMonth actual = YearMonth.from(hoy);
        if (mes.equals(actual)) {
            return new Corte(true, false, hoy.getDayOfMonth());
        }
        return mes.isBefore(actual) ? new Corte(false, false, mes.lengthOfMonth()) : new Corte(false, true, 0);
    }

    private Comparacion comparacion(YearMonth mes, Corte corte) {
        YearMonth anterior = mes.minusMonths(1);
        if (corte.futuro()) {
            return new Comparacion(anterior, pesos(BigDecimal.ZERO), 0, false, null, Direccion.SIN_DATOS, true);
        }
        // En el mes actual: del 1 a hoy contra del 1 al mismo día (o al último, si el anterior es más corto).
        int hastaDia = corte.mesActual() ? Math.min(corte.dias(), anterior.lengthOfMonth()) : anterior.lengthOfMonth();
        if (!inicio.hayDatosDesde(anterior.atDay(1))) {
            return new Comparacion(anterior, null, hastaDia, !corte.mesActual(), null, Direccion.SIN_DATOS, false);
        }
        BigDecimal totalAnterior = total(anterior.atDay(1), anterior.atDay(hastaDia).plusDays(1));
        BigDecimal totalActual = total(mes.atDay(1), mes.atDay(corte.dias()).plusDays(1));

        if (totalAnterior.signum() == 0) {
            return new Comparacion(anterior, pesos(totalAnterior), hastaDia, !corte.mesActual(), null,
                    Direccion.SIN_DATOS, true);
        }
        int variacion = totalActual.subtract(totalAnterior).multiply(CIEN)
                .divide(totalAnterior, 0, RoundingMode.HALF_UP).intValue();
        int signo = totalActual.compareTo(totalAnterior);
        Direccion direccion = signo > 0 ? Direccion.SUBE : signo < 0 ? Direccion.BAJA : Direccion.IGUAL;
        return new Comparacion(anterior, pesos(totalAnterior), hastaDia, !corte.mesActual(), variacion, direccion,
                true);
    }

    /** Total gastado en [desde, hasta). */
    private BigDecimal total(LocalDate desde, LocalDate hasta) {
        LocalDateTime d = desde.atStartOfDay();
        LocalDateTime h = hasta.atStartOfDay();
        return consultas.totalGastos(d, h);
    }

    private static Ingresos ingresos(IngresoService.ResumenIngresos r) {
        return new Ingresos(pesos(r.total()), pesos(r.sueldo()), pesos(r.aguinaldo()), pesos(r.extras()),
                r.cantidadExtras());
    }

    static IngresoDelMes ingreso(Movimiento m) {
        String billetera = m.getCuenta() != null ? m.getCuenta().getNombre()
                : m.getMedioPago() != null && m.getMedioPago().isEfectivo() ? "Efectivo"
                : "Sin especificar";
        return new IngresoDelMes(m.getId(), m.getFechaHora().toLocalDate(), m.getClaseIngreso().name(),
                m.getClaseIngreso().getNombre(), m.getDescripcion(), billetera, pesos(m.getMonto()));
    }

    private static String color(String propio, Long id) {
        return propio != null && !propio.isBlank()
                ? propio
                : COLORES_DE_RESERVA.get((int) (id % COLORES_DE_RESERVA.size()));
    }

    private static BigDecimal promedio(BigDecimal total, int dias) {
        return dias == 0 ? pesos(BigDecimal.ZERO) : total.divide(BigDecimal.valueOf(dias), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal porcentaje(BigDecimal parte, BigDecimal total) {
        return total.signum() == 0
                ? BigDecimal.ZERO.setScale(1)
                : parte.multiply(CIEN).divide(total, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal pesos(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal dolares(BigDecimal monto) {
        return monto.setScale(4, RoundingMode.HALF_UP);
    }

    private static final class Acumulado {
        final String clave;
        final TipoBilletera tipo;
        final String nombre;
        final String color;
        BigDecimal total = BigDecimal.ZERO;
        long cantidad;

        Acumulado(String clave, TipoBilletera tipo, String nombre, String color) {
            this.clave = clave;
            this.tipo = tipo;
            this.nombre = nombre;
            this.color = color;
        }
    }
}
