package ar.gastito.consulta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/**
 * Las herramientas que puede usar la IA para responder una pregunta. Son consultas fijas, escritas en Java: la IA
 * elige cuál y con qué parámetros, pero nunca escribe SQL. Las fechas llegan ya validadas (los dos días
 * incluidos). Si un filtro nombra algo que no existe, se lanza {@link HerramientaInvalida} con las opciones, para
 * que la IA pueda corregirse.
 */
@Service
public class HerramientasConsulta {

    static final int LIMITE_MOVIMIENTOS = 20;
    static final int LIMITE_COINCIDENCIAS = 5;
    private static final BigDecimal CIEN = new BigDecimal("100");

    private final ConsultaRepository consultas;
    private final CatalogoService catalogos;
    private final IngresoService ingresos;

    public HerramientasConsulta(ConsultaRepository consultas, CatalogoService catalogos, IngresoService ingresos) {
        this.consultas = consultas;
        this.catalogos = catalogos;
        this.ingresos = ingresos;
    }

    /** Un parámetro que no sirve: el mensaje se le devuelve a la IA. */
    public static class HerramientaInvalida extends RuntimeException {
        public HerramientaInvalida(String mensaje) {
            super(mensaje);
        }
    }

    /** Filtros opcionales: null o vacío es "todos". */
    public record Filtros(String categoria, String medioPago, String cuenta, String comercio) {

        public static final Filtros NINGUNO = new Filtros(null, null, null, null);
    }

    // --- Resultados ---

    public record Total(LocalDate desde, LocalDate hasta, Filtros filtros, BigDecimal total, int cantidad) {
    }

    public record CategoriaTotal(String categoria, BigDecimal total, int cantidad) {
    }

    public record PorCategoria(LocalDate desde, LocalDate hasta, BigDecimal total, List<CategoriaTotal> categorias) {
    }

    /**
     * @param diferencia          A menos B
     * @param variacionPorcentaje de A respecto de B, redondeada; null si B fue cero
     */
    public record Comparacion(Total a, Total b, BigDecimal diferencia, Integer variacionPorcentaje,
            List<CategoriaComparada> porCategoria) {
    }

    public record CategoriaComparada(String categoria, BigDecimal totalA, BigDecimal totalB) {
    }

    public record MovimientoListado(LocalDate fecha, String descripcion, String categoria, String medio,
            BigDecimal monto) {
    }

    /** @param encontrados cuántos cumplen los filtros (se listan hasta el límite) */
    public record Listado(LocalDate desde, LocalDate hasta, int encontrados, BigDecimal total,
            List<MovimientoListado> movimientos) {
    }

    /**
     * @param compras      en cuántos tickets aparece
     * @param coincidencias los nombres de los productos que coincidieron (hasta 5)
     */
    public record Producto(String texto, LocalDate desde, LocalDate hasta, BigDecimal unidades, BigDecimal total,
            int compras, List<String> coincidencias) {
    }

    /** @param porcentajeGastado qué parte de los ingresos se gastó; null sin ingresos */
    public record IngresosYDisponible(String mes, BigDecimal sueldo, BigDecimal aguinaldo, BigDecimal extras,
            BigDecimal ingresos, BigDecimal gastado, BigDecimal disponible, Integer porcentajeGastado) {
    }

    // --- Herramientas ---

    @Transactional(readOnly = true)
    public Total totalGastado(LocalDate desde, LocalDate hasta, Filtros filtros) {
        List<Movimiento> gastos = filtrar(gastos(desde, hasta), filtros, catalogos.cargar());
        return new Total(desde, hasta, filtros, suma(gastos), gastos.size());
    }

    @Transactional(readOnly = true)
    public PorCategoria gastoPorCategoria(LocalDate desde, LocalDate hasta) {
        List<Movimiento> gastos = gastos(desde, hasta);
        return new PorCategoria(desde, hasta, suma(gastos), porCategoria(gastos));
    }

    @Transactional(readOnly = true)
    public Comparacion compararPeriodos(LocalDate desdeA, LocalDate hastaA, LocalDate desdeB, LocalDate hastaB) {
        List<Movimiento> a = gastos(desdeA, hastaA);
        List<Movimiento> b = gastos(desdeB, hastaB);
        BigDecimal totalA = suma(a);
        BigDecimal totalB = suma(b);
        Map<String, BigDecimal> porCategoriaA = totales(a);
        Map<String, BigDecimal> porCategoriaB = totales(b);
        List<CategoriaComparada> categorias = new ArrayList<>();
        porCategoriaA.keySet().forEach(c -> categorias.add(new CategoriaComparada(c, porCategoriaA.get(c),
                porCategoriaB.getOrDefault(c, cero()))));
        porCategoriaB.keySet().stream().filter(c -> !porCategoriaA.containsKey(c))
                .forEach(c -> categorias.add(new CategoriaComparada(c, cero(), porCategoriaB.get(c))));
        categorias.sort(Comparator.comparing((CategoriaComparada c) -> c.totalA().max(c.totalB())).reversed());
        return new Comparacion(new Total(desdeA, hastaA, Filtros.NINGUNO, totalA, a.size()),
                new Total(desdeB, hastaB, Filtros.NINGUNO, totalB, b.size()), totalA.subtract(totalB),
                variacion(totalA, totalB), categorias);
    }

    /** @param limite cuántos listar, de 1 a {@value #LIMITE_MOVIMIENTOS} (los más nuevos primero) */
    @Transactional(readOnly = true)
    public Listado listarMovimientos(LocalDate desde, LocalDate hasta, Filtros filtros, int limite) {
        int tope = Math.max(1, Math.min(LIMITE_MOVIMIENTOS, limite));
        List<Movimiento> gastos = filtrar(gastos(desde, hasta), filtros, catalogos.cargar());
        List<MovimientoListado> movimientos = gastos.stream().limit(tope)
                .map(m -> new MovimientoListado(m.getFechaHora().toLocalDate(), m.getDescripcion(),
                        m.getCategoria() == null ? null : m.getCategoria().getNombre(), medio(m), pesos(m.getMonto())))
                .toList();
        return new Listado(desde, hasta, gastos.size(), suma(gastos), movimientos);
    }

    /** Sobre el detalle de los tickets: cuántas unidades y cuánta plata (sin descuentos). */
    @Transactional(readOnly = true)
    public Producto buscarProducto(String texto, LocalDate desde, LocalDate hasta) {
        String buscado = normalizar(texto);
        if (buscado.isEmpty()) {
            throw new HerramientaInvalida("Falta qué producto buscar");
        }
        List<MovimientoItem> items = consultas.productos(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay())
                .stream()
                .filter(i -> normalizar(i.getDescripcion()).contains(buscado)
                        || (i.getDescripcionOriginal() != null && normalizar(i.getDescripcionOriginal()).contains(buscado)))
                .toList();
        BigDecimal unidades = items.stream().map(i -> i.getCantidad() == null ? BigDecimal.ONE : i.getCantidad())
                .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
        BigDecimal total = pesos(items.stream().map(MovimientoItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        int compras = (int) items.stream().map(i -> i.getMovimiento().getId()).distinct().count();
        List<String> nombres = items.stream().map(MovimientoItem::getDescripcion).distinct()
                .limit(LIMITE_COINCIDENCIAS).toList();
        return new Producto(texto.strip(), desde, hasta, unidades, total, compras, nombres);
    }

    @Transactional(readOnly = true)
    public IngresosYDisponible ingresosYDisponible(YearMonth mes) {
        IngresoService.ResumenIngresos r = ingresos.delMes(mes);
        BigDecimal gastado = suma(gastos(mes.atDay(1), mes.atEndOfMonth()));
        BigDecimal entrado = pesos(r.total());
        return new IngresosYDisponible(mes.toString(), pesos(r.sueldo()), pesos(r.aguinaldo()), pesos(r.extras()),
                entrado, gastado, pesos(entrado.subtract(gastado)),
                entrado.signum() == 0 ? null
                        : gastado.multiply(CIEN).divide(entrado, 0, RoundingMode.HALF_UP).intValue());
    }

    // --- Filtros ---

    private List<Movimiento> gastos(LocalDate desde, LocalDate hasta) {
        return consultas.gastos(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
    }

    private static List<Movimiento> filtrar(List<Movimiento> gastos, Filtros f, Catalogo catalogo) {
        Predicate<Movimiento> filtro = m -> true;
        if (lleno(f.categoria())) {
            Long id = opcion(f.categoria(), catalogo.categorias(), "categoría").getId();
            filtro = filtro.and(m -> m.getCategoria() != null && id.equals(m.getCategoria().getId()));
        }
        if (lleno(f.medioPago())) {
            Long id = opcion(f.medioPago(), catalogo.mediosPago(), "medio de pago").getId();
            filtro = filtro.and(m -> m.getMedioPago() != null && id.equals(m.getMedioPago().getId()));
        }
        if (lleno(f.cuenta())) {
            Long id = opcion(f.cuenta(), catalogo.cuentas(), "cuenta").getId();
            filtro = filtro.and(m -> m.getCuenta() != null && id.equals(m.getCuenta().getId()));
        }
        if (lleno(f.comercio())) {
            String buscado = normalizar(f.comercio());
            filtro = filtro.and(m -> normalizar(m.getDescripcion()).contains(buscado)
                    || (m.getComercio() != null && normalizar(m.getComercio()).contains(buscado)));
        }
        return gastos.stream().filter(filtro).toList();
    }

    private static <T extends ConPalabrasClave> T opcion(String nombre, List<T> opciones, String que) {
        Optional<T> encontrada = ValidadorInterpretacion.buscar(nombre, opciones);
        return encontrada.orElseThrow(() -> new HerramientaInvalida("No existe la " + que + " \"" + nombre
                + "\". Las que hay: " + opciones.stream().map(ConPalabrasClave::getNombre)
                        .collect(Collectors.joining(", "))));
    }

    // --- Cuentas ---

    private static List<CategoriaTotal> porCategoria(List<Movimiento> gastos) {
        Map<String, List<Movimiento>> grupos = gastos.stream().filter(m -> m.getCategoria() != null)
                .collect(Collectors.groupingBy(m -> m.getCategoria().getNombre(), LinkedHashMap::new,
                        Collectors.toList()));
        return grupos.entrySet().stream()
                .map(e -> new CategoriaTotal(e.getKey(), suma(e.getValue()), e.getValue().size()))
                .sorted(Comparator.comparing(CategoriaTotal::total).reversed())
                .toList();
    }

    private static Map<String, BigDecimal> totales(List<Movimiento> gastos) {
        Map<String, BigDecimal> totales = new LinkedHashMap<>();
        porCategoria(gastos).forEach(c -> totales.put(c.categoria(), c.total()));
        return totales;
    }

    private static String medio(Movimiento m) {
        if (m.getMedioPago() != null && m.getCuenta() != null) {
            return m.getMedioPago().getNombre() + " · " + m.getCuenta().getNombre();
        }
        if (m.getMedioPago() != null) {
            return m.getMedioPago().getNombre();
        }
        return m.getCuenta() != null ? m.getCuenta().getNombre() : "Sin especificar";
    }

    private static BigDecimal suma(List<Movimiento> gastos) {
        return pesos(gastos.stream().map(Movimiento::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static Integer variacion(BigDecimal actual, BigDecimal anterior) {
        return anterior.signum() == 0 ? null
                : actual.subtract(anterior).multiply(CIEN).divide(anterior, 0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal pesos(BigDecimal monto) {
        return (monto == null ? BigDecimal.ZERO : monto).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal cero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static boolean lleno(String texto) {
        return texto != null && !texto.isBlank();
    }

    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
    }
}
