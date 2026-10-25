package ar.gastito.resumen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/**
 * Los números de un resumen, calculados en Java sobre la base (regla 2: la IA solo los cuenta). Compara contra el
 * período anterior hasta el mismo punto, así un resumen parcial no se compara con una semana o un mes entero.
 */
@Service
public class CalculoResumen {

    static final int PRODUCTOS = 5;
    static final BigDecimal HORMIGA_OBJETIVO_POR_SEMANA = new BigDecimal("3");
    static final BigDecimal RECORTE_CATEGORIA_MAS_GRANDE = new BigDecimal("0.10");
    private static final BigDecimal DIAS_POR_MES = new BigDecimal("30.4");
    private static final BigDecimal CIEN = new BigDecimal("100");
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE d", ES_AR);

    private final ResumenConsultas consultas;
    private final IngresoService ingresos;
    private final RegistroUsoIa usoIa;
    private final GastitoProperties.Resumenes config;

    public CalculoResumen(ResumenConsultas consultas, IngresoService ingresos, RegistroUsoIa usoIa,
            GastitoProperties properties) {
        this.consultas = consultas;
        this.ingresos = ingresos;
        this.usoIa = usoIa;
        this.config = properties.resumenes();
    }

    /** @param hoy si el período no terminó, los datos llegan hasta hoy (resumen parcial) */
    @Transactional(readOnly = true)
    public DatosResumen calcular(PeriodoResumen periodo, LocalDate hoy) {
        LocalDate hasta = periodo.hastaParaDatos(hoy);
        boolean parcial = hasta.isBefore(periodo.hasta());
        int dias = (int) periodo.diasHasta(hasta);
        List<Movimiento> gastos = consultas.gastos(periodo.desde().atStartOfDay(), hasta.plusDays(1).atStartOfDay());

        PeriodoResumen anterior = periodo.anterior();
        LocalDate hastaAnterior = periodo.mismoPuntoEnAnterior(hasta);
        List<Movimiento> gastosAnteriores = consultas.gastos(anterior.desde().atStartOfDay(),
                hastaAnterior.plusDays(1).atStartOfDay());

        BigDecimal total = suma(gastos);
        BigDecimal totalAnterior = suma(gastosAnteriores);
        List<DatosResumen.Categoria> categorias = porCategoria(gastos, gastosAnteriores);
        DatosResumen.Hormiga hormiga = hormiga(gastos, dias);

        DatosResumen.Ingresos ingresosDelMes = null;
        BigDecimal costoIa = null;
        if (periodo.tipo() == TipoResumen.MENSUAL) {
            YearMonth mes = YearMonth.from(periodo.desde());
            IngresoService.ResumenIngresos r = ingresos.delMes(mes);
            BigDecimal totalIngresos = pesos(r.total());
            ingresosDelMes = new DatosResumen.Ingresos(pesos(r.sueldo()), pesos(r.aguinaldo()), pesos(r.extras()),
                    totalIngresos, pesos(totalIngresos.subtract(total)), porcentaje(total, totalIngresos));
            costoIa = usoIa.resumenDe(mes).costoUsd().setScale(4, RoundingMode.HALF_UP);
        }

        return new DatosResumen(
                new DatosResumen.Periodo(periodo.tipo().name(), periodo.desde(), hasta, periodo.hasta(), parcial, dias),
                total,
                gastos.size(),
                new DatosResumen.Comparacion(anterior.desde(), hastaAnterior, totalAnterior,
                        variacion(total, totalAnterior)),
                categorias,
                gastoMasGrande(gastos),
                hormiga,
                diasSinGastos(gastos, periodo.desde(), hasta),
                sinEspecificar(gastos),
                productos(periodo.desde(), hasta),
                escenarios(categorias, hormiga, dias),
                ingresosDelMes,
                costoIa,
                aclaraciones(gastos, parcial, hasta));
    }

    // --- Categorías y gasto más grande ---

    private static List<DatosResumen.Categoria> porCategoria(List<Movimiento> gastos, List<Movimiento> anteriores) {
        Map<String, List<Movimiento>> actuales = agrupar(gastos);
        Map<String, List<Movimiento>> previos = agrupar(anteriores);
        return actuales.entrySet().stream()
                .map(e -> {
                    Movimiento ejemplo = e.getValue().get(0);
                    BigDecimal total = suma(e.getValue());
                    BigDecimal totalAnterior = suma(previos.getOrDefault(e.getKey(), List.of()));
                    return new DatosResumen.Categoria(e.getKey(), ejemplo.getCategoria().getEmoji(), total,
                            e.getValue().size(), totalAnterior, variacion(total, totalAnterior));
                })
                .sorted(Comparator.comparing(DatosResumen.Categoria::total).reversed()
                        .thenComparing(DatosResumen.Categoria::nombre))
                .toList();
    }

    private static Map<String, List<Movimiento>> agrupar(List<Movimiento> gastos) {
        return gastos.stream().filter(m -> m.getCategoria() != null).collect(Collectors.groupingBy(
                m -> m.getCategoria().getNombre(), LinkedHashMap::new, Collectors.toList()));
    }

    private static DatosResumen.GastoGrande gastoMasGrande(List<Movimiento> gastos) {
        return gastos.stream()
                .max(Comparator.comparing(Movimiento::getMonto))
                .map(m -> new DatosResumen.GastoGrande(m.getDescripcion(),
                        m.getCategoria() == null ? null : m.getCategoria().getNombre(), pesos(m.getMonto()),
                        m.getFechaHora().toLocalDate()))
                .orElse(null);
    }

    // --- Gastos hormiga y días sin gastos ---

    DatosResumen.Hormiga hormiga(List<Movimiento> gastos, int dias) {
        Set<String> categorias = config.categoriasHormiga().stream()
                .map(c -> c.toLowerCase(ES_AR)).collect(Collectors.toSet());
        List<Movimiento> chicas = gastos.stream()
                .filter(m -> m.getCategoria() != null
                        && categorias.contains(m.getCategoria().getNombre().toLowerCase(ES_AR)))
                .filter(m -> m.getMonto().compareTo(config.umbralHormiga()) < 0)
                .toList();
        BigDecimal total = suma(chicas);
        BigDecimal promedio = chicas.isEmpty() ? pesos(BigDecimal.ZERO)
                : total.divide(BigDecimal.valueOf(chicas.size()), 2, RoundingMode.HALF_UP);
        BigDecimal porSemana = BigDecimal.valueOf(chicas.size()).multiply(BigDecimal.valueOf(7))
                .divide(BigDecimal.valueOf(dias), 1, RoundingMode.HALF_UP);
        return new DatosResumen.Hormiga(pesos(config.umbralHormiga()), config.categoriasHormiga(), chicas.size(),
                total, promedio, porSemana);
    }

    static int diasSinGastos(List<Movimiento> gastos, LocalDate desde, LocalDate hasta) {
        Set<LocalDate> conGastos = new HashSet<>();
        gastos.forEach(m -> conGastos.add(m.getFechaHora().toLocalDate()));
        int sin = 0;
        for (LocalDate dia = desde; !dia.isAfter(hasta); dia = dia.plusDays(1)) {
            if (!conGastos.contains(dia)) {
                sin++;
            }
        }
        return sin;
    }

    // --- Productos de los tickets ---

    /**
     * Agrupa por código de barras (el mismo producto en distintos tickets) o, si no tiene, por la descripción. El
     * nombre es la descripción legible del renglón más reciente.
     */
    private DatosResumen.Productos productos(LocalDate desde, LocalDate hasta) {
        List<MovimientoItem> items = consultas.productos(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
        Map<String, List<MovimientoItem>> porProducto = items.stream().collect(Collectors.groupingBy(
                i -> i.getCodigoBarras() != null ? i.getCodigoBarras() : i.getDescripcion().toLowerCase(ES_AR).strip(),
                LinkedHashMap::new, Collectors.toList()));
        List<DatosResumen.Producto> productos = porProducto.values().stream()
                .map(grupo -> new DatosResumen.Producto(grupo.get(grupo.size() - 1).getDescripcion(),
                        grupo.stream().map(i -> i.getCantidad() == null ? BigDecimal.ONE : i.getCantidad())
                                .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros(),
                        pesos(grupo.stream().map(MovimientoItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add))))
                .toList();
        return new DatosResumen.Productos(
                productos.stream().sorted(Comparator.comparing(DatosResumen.Producto::unidades)
                        .thenComparing(DatosResumen.Producto::total).reversed()).limit(PRODUCTOS).toList(),
                productos.stream().sorted(Comparator.comparing(DatosResumen.Producto::total).reversed())
                        .limit(PRODUCTOS).toList());
    }

    // --- Escenarios de ahorro ---

    /**
     * Hasta tres ahorros posibles, con los números ya hechos: los gastos hormiga a 3 por semana, la categoría que más
     * subió de vuelta al nivel del período anterior y la categoría más grande con un 10% menos.
     */
    static List<DatosResumen.Escenario> escenarios(List<DatosResumen.Categoria> categorias,
            DatosResumen.Hormiga hormiga, int dias) {
        List<DatosResumen.Escenario> escenarios = new ArrayList<>();
        if (hormiga.porSemana().compareTo(HORMIGA_OBJETIVO_POR_SEMANA) > 0) {
            BigDecimal semanas = BigDecimal.valueOf(dias).divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
            BigDecimal ahorro = hormiga.porSemana().subtract(HORMIGA_OBJETIVO_POR_SEMANA).multiply(semanas)
                    .multiply(hormiga.promedio());
            escenarios.add(new DatosResumen.Escenario("HORMIGA",
                    "Bajar los gastos hormiga a " + HORMIGA_OBJETIVO_POR_SEMANA + " por semana", null,
                    hormiga.porSemana(), HORMIGA_OBJETIVO_POR_SEMANA, redondear(ahorro),
                    alMes(ahorro, dias)));
        }
        categorias.stream()
                .filter(c -> c.total().compareTo(c.totalAnterior()) > 0)
                .max(Comparator.comparing(c -> c.total().subtract(c.totalAnterior())))
                .ifPresent(c -> {
                    BigDecimal ahorro = c.total().subtract(c.totalAnterior());
                    escenarios.add(new DatosResumen.Escenario("CATEGORIA_QUE_MAS_SUBIO",
                            "Volver al gasto del período anterior en " + c.nombre(), c.nombre(), c.total(),
                            c.totalAnterior(), redondear(ahorro), alMes(ahorro, dias)));
                });
        if (!categorias.isEmpty()) {
            DatosResumen.Categoria mayor = categorias.get(0);
            BigDecimal ahorro = mayor.total().multiply(RECORTE_CATEGORIA_MAS_GRANDE);
            escenarios.add(new DatosResumen.Escenario("CATEGORIA_MAS_GRANDE",
                    "Gastar un 10% menos en " + mayor.nombre(), mayor.nombre(), mayor.total(),
                    pesos(mayor.total().subtract(ahorro)), redondear(ahorro), alMes(ahorro, dias)));
        }
        return escenarios;
    }

    /** Lo ahorrado en el período, llevado a un mes de 30,4 días y redondeado a la centena. */
    private static BigDecimal alMes(BigDecimal ahorroPeriodo, int dias) {
        BigDecimal mensual = ahorroPeriodo.multiply(DIAS_POR_MES).divide(BigDecimal.valueOf(dias), 2,
                RoundingMode.HALF_UP);
        return mensual.divide(CIEN, 0, RoundingMode.HALF_UP).multiply(CIEN).setScale(2);
    }

    private static BigDecimal redondear(BigDecimal monto) {
        return monto.setScale(0, RoundingMode.HALF_UP).setScale(2);
    }

    // --- Aclaraciones ---

    /** Lo que no se puede calcular bien todavía: se dice, no se inventa. */
    private static List<String> aclaraciones(List<Movimiento> gastos, boolean parcial, LocalDate hasta) {
        List<String> aclaraciones = new ArrayList<>();
        if (parcial) {
            aclaraciones.add("Período parcial: los datos llegan hasta el " + DIA.format(hasta) + ".");
        }
        int sinMedio = sinEspecificar(gastos).cantidad();
        if (sinMedio > 0) {
            aclaraciones.add(sinMedio + (sinMedio == 1 ? " gasto" : " gastos")
                    + " sin medio de pago (Sin especificar): no se pueden repartir por billetera.");
        }
        aclaraciones.add("La plata que te deben de juntadas todavía no se registra: llega en la etapa 6.");
        return aclaraciones;
    }

    private static DatosResumen.SinEspecificar sinEspecificar(List<Movimiento> gastos) {
        List<Movimiento> sinMedio = gastos.stream()
                .filter(m -> m.getMedioPago() == null && m.getCuenta() == null && m.getTarjeta() == null)
                .toList();
        return new DatosResumen.SinEspecificar(sinMedio.size(), suma(sinMedio));
    }

    // --- Cuentas ---

    private static BigDecimal suma(List<Movimiento> gastos) {
        return pesos(gastos.stream().map(Movimiento::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal pesos(BigDecimal monto) {
        return (monto == null ? BigDecimal.ZERO : monto).setScale(2, RoundingMode.HALF_UP);
    }

    /** Variación porcentual redondeada; null si el anterior fue cero. */
    static Integer variacion(BigDecimal actual, BigDecimal anterior) {
        if (anterior.signum() == 0) {
            return null;
        }
        return actual.subtract(anterior).multiply(CIEN).divide(anterior, 0, RoundingMode.HALF_UP).intValue();
    }

    /** Qué porcentaje de {@code de} es {@code parte}, redondeado; null si {@code de} es cero. */
    private static Integer porcentaje(BigDecimal parte, BigDecimal de) {
        return de.signum() == 0 ? null : parte.multiply(CIEN).divide(de, 0, RoundingMode.HALF_UP).intValue();
    }
}
