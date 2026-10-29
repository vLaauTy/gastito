package ar.gastito.resumen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.InicioRegistro;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/**
 * Los números de un resumen, calculados en Java sobre la base (regla 2: la IA solo los cuenta). Compara contra el
 * período anterior hasta el mismo punto, así un resumen parcial no se compara con una semana o un mes entero.
 */
@Service
public class CalculoResumen {

    private static final Logger log = LoggerFactory.getLogger(CalculoResumen.class);

    static final int PRODUCTOS = 5;
    static final BigDecimal HORMIGA_OBJETIVO_POR_SEMANA = new BigDecimal("3");
    static final BigDecimal RECORTE_CATEGORIA_MAS_GRANDE = new BigDecimal("0.10");
    private static final BigDecimal DIAS_POR_MES = new BigDecimal("30.4");
    private static final BigDecimal CIEN = new BigDecimal("100");
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private final ResumenConsultas consultas;
    private final IngresoService ingresos;
    private final RegistroUsoIa usoIa;
    private final InicioRegistro inicio;
    private final GastitoProperties.Resumenes config;

    public CalculoResumen(ResumenConsultas consultas, IngresoService ingresos, RegistroUsoIa usoIa,
            InicioRegistro inicio, GastitoProperties properties) {
        this.consultas = consultas;
        this.inicio = inicio;
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

        // Si el registro empezó después del inicio del período anterior, ese período no tiene datos: no es $0.
        boolean conDatos = inicio.hayDatosDesde(anterior.desde());
        BigDecimal total = suma(gastos);
        BigDecimal totalAnterior = conDatos ? suma(gastosAnteriores) : null;
        List<DatosResumen.Categoria> categorias = porCategoria(gastos, conDatos ? gastosAnteriores : null);
        DatosResumen.Hormiga hormiga = hormiga(gastos, dias);
        int diasSinGastos = diasSinGastos(gastos, periodo.desde(), hasta);
        boolean semanal = periodo.tipo() == TipoResumen.SEMANAL;
        boolean pocosDatos = pocosDatos(gastos.size(), dias - diasSinGastos, config.minimo(semanal));
        // En el semanal, llevar un ahorro a un mes pide al menos 2 semanas con gastos en la categoría: esta y alguna
        // anterior. El mensual ya es un mes.
        Predicate<List<String>> alMes = semanal
                ? cats -> consultas.hayGastosAntes(cats.stream().map(c -> c.toLowerCase(ES_AR)).toList(),
                        periodo.desde().atStartOfDay())
                : cats -> true;

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
                new DatosResumen.Comparacion(anterior.desde(), hastaAnterior, conDatos, totalAnterior,
                        conDatos ? variacion(total, totalAnterior) : null),
                categorias,
                gastoMasGrande(gastos),
                hormiga,
                diasSinGastos,
                sinEspecificar(gastos),
                productos(periodo.desde(), hasta),
                pocosDatos ? List.of() : escenarios(categorias, hormiga, dias, alMes),
                ingresosDelMes,
                costoIa,
                pocosDatos,
                notas(gastos));
    }

    // --- Categorías y gasto más grande ---

    /** @param anteriores null si el período anterior no tiene datos: el total anterior de cada categoría queda null */
    private static List<DatosResumen.Categoria> porCategoria(List<Movimiento> gastos, List<Movimiento> anteriores) {
        Map<String, List<Movimiento>> actuales = agrupar(gastos);
        Map<String, List<Movimiento>> previos = anteriores == null ? null : agrupar(anteriores);
        return actuales.entrySet().stream()
                .map(e -> {
                    Movimiento ejemplo = e.getValue().get(0);
                    BigDecimal total = suma(e.getValue());
                    BigDecimal totalAnterior = previos == null ? null
                            : suma(previos.getOrDefault(e.getKey(), List.of()));
                    return new DatosResumen.Categoria(e.getKey(), ejemplo.getCategoria().getEmoji(), total,
                            e.getValue().size(), totalAnterior,
                            totalAnterior == null ? null : variacion(total, totalAnterior));
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
     * subió de vuelta al nivel del período anterior (solo si ese período tiene datos y ahí se gastó algo) y la categoría
     * más grande con un 10% menos. Ninguno promete ahorrar más de lo gastado (ver {@link #dentroDeLoGastado}).
     *
     * @param alMes si los ahorros de esas categorías se pueden llevar a un mes; si no, ahorroMensual queda null
     */
    static List<DatosResumen.Escenario> escenarios(List<DatosResumen.Categoria> categorias,
            DatosResumen.Hormiga hormiga, int dias, Predicate<List<String>> alMes) {
        List<DatosResumen.Escenario> escenarios = new ArrayList<>();
        if (hormiga.porSemana().compareTo(HORMIGA_OBJETIVO_POR_SEMANA) > 0) {
            BigDecimal semanas = BigDecimal.valueOf(dias).divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
            BigDecimal ahorro = hormiga.porSemana().subtract(HORMIGA_OBJETIVO_POR_SEMANA).multiply(semanas)
                    .multiply(hormiga.promedio());
            escenarios.add(new DatosResumen.Escenario("HORMIGA",
                    "Bajar los gastos hormiga a " + HORMIGA_OBJETIVO_POR_SEMANA + " por semana", null,
                    hormiga.porSemana(), HORMIGA_OBJETIVO_POR_SEMANA, redondear(ahorro),
                    alMes.test(hormiga.categorias()) ? alMes(ahorro, dias) : null));
        }
        categorias.stream()
                .filter(c -> c.totalAnterior() != null && c.totalAnterior().signum() > 0
                        && c.total().compareTo(c.totalAnterior()) > 0)
                .max(Comparator.comparing(c -> c.total().subtract(c.totalAnterior())))
                .ifPresent(c -> {
                    BigDecimal ahorro = c.total().subtract(c.totalAnterior());
                    escenarios.add(new DatosResumen.Escenario("CATEGORIA_QUE_MAS_SUBIO",
                            "Volver al gasto del período anterior en " + c.nombre(), c.nombre(), c.total(),
                            c.totalAnterior(), redondear(ahorro),
                            alMes.test(List.of(c.nombre())) ? alMes(ahorro, dias) : null));
                });
        if (!categorias.isEmpty()) {
            DatosResumen.Categoria mayor = categorias.get(0);
            BigDecimal ahorro = mayor.total().multiply(RECORTE_CATEGORIA_MAS_GRANDE);
            escenarios.add(new DatosResumen.Escenario("CATEGORIA_MAS_GRANDE",
                    "Gastar un 10% menos en " + mayor.nombre(), mayor.nombre(), mayor.total(),
                    pesos(mayor.total().subtract(ahorro)), redondear(ahorro),
                    alMes.test(List.of(mayor.nombre())) ? alMes(ahorro, dias) : null));
        }
        return escenarios.stream().filter(e -> dentroDeLoGastado(e, categorias, hormiga, dias)).toList();
    }

    /**
     * Un escenario no puede prometer un ahorro mayor a lo gastado en el período en esa categoría (o en gastos
     * hormiga), ni, llevado a un mes, mayor a ese gasto llevado a un mes. Si pasa, es un error de cálculo: se saca.
     */
    static boolean dentroDeLoGastado(DatosResumen.Escenario e, List<DatosResumen.Categoria> categorias,
            DatosResumen.Hormiga hormiga, int dias) {
        BigDecimal gastado = e.categoria() == null ? hormiga.total()
                : categorias.stream().filter(c -> c.nombre().equals(e.categoria())).map(DatosResumen.Categoria::total)
                        .findFirst().orElse(BigDecimal.ZERO);
        boolean bien = e.ahorroPeriodo().compareTo(gastado) <= 0
                && (e.ahorroMensual() == null || e.ahorroMensual().compareTo(alMes(gastado, dias)) <= 0);
        if (!bien) {
            log.warn("Se saca el escenario {}: promete ahorrar {} y en el período se gastó {}", e.tipo(),
                    e.ahorroPeriodo(), gastado);
        }
        return bien;
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

    // --- Datos mínimos y notas ---

    /** Con menos gastos, o con gastos en menos días distintos, que el mínimo, no se da consejo. */
    static boolean pocosDatos(int gastos, int diasConGastos, GastitoProperties.Minimo minimo) {
        return gastos < minimo.gastos() || diasConGastos < minimo.dias();
    }

    /**
     * Notas al pie, solo sobre datos que existen en el período: lo que todavía no se puede calcular no se menciona
     * si no hay nada de eso.
     */
    static List<String> notas(List<Movimiento> gastos) {
        List<String> notas = new ArrayList<>();
        long juntadas = gastos.stream().filter(Movimiento::isJuntada).count();
        if (juntadas > 0) {
            notas.add(juntadas + (juntadas == 1 ? " gasto es de una juntada" : " gastos son de juntadas")
                    + ": lo que te devuelvan todavía no se descuenta del total.");
        }
        List<Movimiento> estimados = gastos.stream().filter(Movimiento::isMontoEstimado).toList();
        if (!estimados.isEmpty()) {
            String nombres = estimados.stream().map(m -> m.getDescripcion() == null ? "un fijo"
                    : m.getDescripcion().toLowerCase(Locale.forLanguageTag("es-AR"))).distinct()
                    .collect(Collectors.joining(", "));
            notas.add("Quedaron con monto estimado: " + nombres + " (" + pesosArg(suma(estimados))
                    + " del total es estimado). Si sabés el real, decímelo.");
        }
        return notas;
    }

    /** "$170.000" o "$1.234,50", para las notas al pie (el resto de los números los formatea el bot). */
    private static String pesosArg(BigDecimal monto) {
        DecimalFormat formato = new DecimalFormat(monto.stripTrailingZeros().scale() > 0 ? "#,##0.00" : "#,##0",
                DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));
        return "$" + formato.format(monto);
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
