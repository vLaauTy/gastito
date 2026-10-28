package ar.gastito.prueba;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.config.GastitoProperties;
import ar.gastito.fijo.Frecuencia;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.fijo.VistaFijo;
import ar.gastito.ia.CalculadoraCostoIa;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.ReciboLeido;
import ar.gastito.recibo.ReciboService;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.SueldoService;

/**
 * Borra los datos de la base de prueba y la vuelve a llenar con {@link PlanDatosPrueba}. Solo existe con el perfil
 * prueba y gastito.prueba.generar=true, y antes de tocar nada comprueba que la base sea gastito_prueba: con la base
 * real (perfil postgres) no corre nunca. Al terminar, apaga la aplicación.
 * <p>
 * Borra movimientos (con sus productos y categorías candidatas, y por lo tanto lo aprendido), recibos de sueldo,
 * resúmenes y envíos de resúmenes, y las fotos de los tickets y los recibos de prueba. Deja el catálogo y las llamadas
 * de uso_ia que no sean de prueba, que son plata de verdad gastada en la API; las de prueba (las que genera acá, con
 * el modelo marcado "(prueba)") se borran y se vuelven a generar.
 */
@Component
@Profile("prueba")
@ConditionalOnProperty(prefix = "gastito.prueba", name = "generar", havingValue = "true")
public class GeneradorDatosPrueba implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeneradorDatosPrueba.class);

    /** El nombre que tiene que tener la base: cualquier otra, y no se toca. */
    static final String BASE_DE_PRUEBA = "gastito_prueba";
    static final int MESES = 3;
    static final long SEMILLA = 20261025L;

    private static final List<String> BORRAR = List.of(
            "UPDATE uso_ia SET movimiento_id = NULL",
            "UPDATE juntada_participante SET gasto_perdido_id = NULL, devolucion_id = NULL",
            "DELETE FROM aviso_tope_ia",
            "DELETE FROM uso_ia WHERE modelo LIKE '%" + PlanUsoIaPrueba.MARCA + "'",
            "DELETE FROM resumen_envio",
            "DELETE FROM resumen",
            "DELETE FROM movimiento_item",
            "DELETE FROM movimiento_categoria_candidata",
            "DELETE FROM mapeo_concepto_recibo",
            "DELETE FROM recibo_concepto",
            "DELETE FROM recibo_sueldo",
            "DELETE FROM calculo_sueldo_linea",
            "DELETE FROM calculo_sueldo",
            "DELETE FROM escala_valor",
            "DELETE FROM escala_sueldo",
            "DELETE FROM movimiento",
            "DELETE FROM recordatorio_fijo",
            "DELETE FROM gasto_fijo");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;
    private final CatalogoService catalogos;
    private final MovimientoService movimientos;
    private final IngresoService ingresos;
    private final GastoFijoService fijos;
    private final ReciboService recibos;
    private final SueldoService sueldo;
    private final CalculadoraCostoIa calculadora;
    private final GastitoProperties properties;
    private final Environment entorno;
    private final ApplicationContext contexto;
    private final Clock clock;

    public GeneradorDatosPrueba(DataSource dataSource, JdbcTemplate jdbc, TransactionTemplate transaccion,
            CatalogoService catalogos, MovimientoService movimientos, IngresoService ingresos,
            GastoFijoService fijos, ReciboService recibos, SueldoService sueldo, CalculadoraCostoIa calculadora,
            GastitoProperties properties, Environment entorno, ApplicationContext contexto,
            Clock clock) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transaccion = transaccion;
        this.catalogos = catalogos;
        this.movimientos = movimientos;
        this.ingresos = ingresos;
        this.fijos = fijos;
        this.recibos = recibos;
        this.sueldo = sueldo;
        this.calculadora = calculadora;
        this.properties = properties;
        this.entorno = entorno;
        this.contexto = contexto;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        comprobarBase(urlDeLaBase(), entorno.acceptsProfiles(Profiles.of("postgres")));

        PlanDatosPrueba.Plan plan = PlanDatosPrueba.armar(LocalDateTime.now(clock), MESES, SEMILLA);
        log.info("Generando datos de prueba del {} al {} ({} gastos, {} ingresos; mes caro: {})", plan.desde(),
                plan.hasta(), plan.gastos().size(), plan.ingresos().size(), plan.mesCaro());

        transaccion.executeWithoutResult(estado -> BORRAR.forEach(jdbc::update));
        borrarArchivos();
        cargar(plan);

        log.info("Listo: la base {} tiene datos de prueba nuevos", BASE_DE_PRUEBA);
        int codigo = SpringApplication.exit(contexto, () -> 0);
        System.exit(codigo);
    }

    /**
     * Solo la base gastito_prueba, y nunca con el perfil postgres activo (aunque la URL apunte a la de prueba).
     *
     * @throws IllegalStateException si no es la base de prueba
     */
    static void comprobarBase(String url, boolean perfilPostgres) {
        if (perfilPostgres) {
            throw new IllegalStateException("El generador de datos de prueba no corre con el perfil postgres");
        }
        String base = url == null ? "" : url.replaceFirst("\\?.*$", "").replaceFirst("^.*/", "");
        if (!BASE_DE_PRUEBA.equals(base)) {
            throw new IllegalStateException("El generador solo carga datos en la base " + BASE_DE_PRUEBA
                    + ", y esta es \"" + base + "\"");
        }
    }

    private String urlDeLaBase() throws SQLException {
        try (Connection conexion = dataSource.getConnection()) {
            return conexion.getMetaData().getURL();
        }
    }

    /** Las fotos de los tickets y los recibos de prueba. */
    private void borrarArchivos() throws IOException {
        for (String nombre : List.of("tickets", "recibos")) {
            Path carpeta = Path.of(properties.ticket().carpetaDatos(), nombre);
            if (!Files.isDirectory(carpeta)) {
                continue;
            }
            try (Stream<Path> archivos = Files.walk(carpeta)) {
                for (Path p : archivos.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    private void cargar(PlanDatosPrueba.Plan plan) {
        Catalogo catalogo = catalogos.cargar();
        Map<String, Categoria> categorias = porNombre(catalogo.categorias(), Categoria::getNombre);
        Map<String, MedioPago> medios = porNombre(catalogo.mediosPago(), MedioPago::getNombre);
        Map<String, Cuenta> cuentas = porNombre(catalogo.cuentas(), Cuenta::getNombre);

        for (PlanDatosPrueba.Gasto g : plan.gastos()) {
            Categoria categoria = buscar(categorias, g.categoria());
            MedioPago medio = buscar(medios, g.medio());
            Cuenta cuenta = g.cuenta() == null ? null : buscar(cuentas, g.cuenta());
            MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(g.monto(), g.descripcion(),
                    categoria, medio, cuenta,
                    PlanDatosPrueba.TARJETA.equals(g.medio()) ? catalogo.tarjetaCredito().orElse(null) : null,
                    g.fechaHora());
            Movimiento m = g.ticket() == null
                    ? movimientos.crearPendiente(interpretado, g.origen(), g.descripcion() + " " + g.monto())
                    : movimientos.crearPendienteDeTicket(interpretado, new DatosTicket(g.ticket().comercio(),
                            g.ticket().sucursal(), g.ticket().subtotal(), g.ticket().descuentos(), List.of(),
                            g.ticket().items()), null);
            movimientos.confirmar(m.getId());
        }
        for (PlanDatosPrueba.Ingreso i : plan.ingresos()) {
            // El sueldo y el aguinaldo vienen de los recibos.
            if (i.clase() == ClaseIngreso.SUELDO || i.clase() == ClaseIngreso.AGUINALDO) {
                continue;
            }
            ingresos.cargar(i.clase(), i.monto(), i.descripcion(),
                    i.cuenta() == null ? null : buscar(cuentas, i.cuenta()).getId(), i.fecha());
        }
        // En una transacción: el recibo de cada mes se arma desde las líneas del cálculo guardado.
        transaccion.executeWithoutResult(estado -> cargarSueldos());
        cargarUsoIa();
        cargarFijos(categorias, catalogo.tarjetaCredito().orElse(null));
    }

    /** Llamadas a la IA de los últimos meses, marcadas como de prueba, con el costo que tendrían con los precios de hoy. */
    private void cargarUsoIa() {
        String modelo = properties.ia().modelo();
        for (PlanUsoIaPrueba.Llamada l : PlanUsoIaPrueba.armar(LocalDateTime.now(clock), MESES, SEMILLA)) {
            jdbc.update("INSERT INTO uso_ia (fecha_hora, modelo, tokens_entrada, tokens_salida, proposito, costo_usd) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", java.sql.Timestamp.valueOf(l.fechaHora()),
                    modelo + PlanUsoIaPrueba.MARCA, l.tokensEntrada(), l.tokensSalida(), l.proposito().name(),
                    calculadora.costo(modelo, l.tokensEntrada(), l.tokensSalida()));
        }
    }

    /**
     * Seis meses de sueldo: dos escalas (una paritaria a mitad de camino), el cálculo de cada mes, lo cobrado en
     * Galicia y el recibo con un PDF inventado. El recibo de un mes trae la ley 19.032 al 2,5% (una cosa rara) y el
     * último mes todavía no tiene recibo: se cobró 4.400 menos de lo calculado. Más el aguinaldo del semestre.
     */
    private void cargarSueldos() {
        YearMonth hasta = YearMonth.from(LocalDateTime.now(clock));
        LocalDate hoy = LocalDate.now(clock);
        Cuenta galicia = buscar(porNombre(catalogos.cargar().cuentas(), Cuenta::getNombre), "Galicia");
        Map<String, Long> conceptos = new HashMap<>();
        sueldo.listarConceptos().forEach(c -> conceptos.put(c.getNombre(), c.getId()));
        for (PlanSueldoPrueba.Escala e : PlanSueldoPrueba.escalas(hasta)) {
            Map<Long, BigDecimal> valores = new HashMap<>();
            e.montos().forEach((nombre, monto) -> valores.put(buscar(conceptos, nombre), monto));
            sueldo.crearEscala(e.desde().atDay(1), e.valorHora(), valores);
        }
        BigDecimal mejorRemuneracion = BigDecimal.ZERO;
        for (int i = 0; i < PlanSueldoPrueba.MESES; i++) {
            YearMonth mes = hasta.minusMonths(PlanSueldoPrueba.MESES - 1L - i);
            sueldo.guardar(mes, sueldo.horasPorOrden(PlanSueldoPrueba.horas(i)), Map.of());
            CalculoSueldo calculo = sueldo.calculoDelMes(mes).orElseThrow();
            LocalDate fechaCobro = mes.atEndOfMonth().isAfter(hoy) ? hoy : mes.atEndOfMonth();
            BigDecimal cobrado;
            if (mes.equals(hasta)) {
                cobrado = calculo.getNetoTotal().subtract(PlanSueldoPrueba.DIFERENCIA_COBRO);
            } else {
                ReciboLeido recibo = PlanRecibosPrueba.sueldo(calculo, i == PlanSueldoPrueba.MES_RARO);
                recibos.guardar(recibo, PlanRecibosPrueba.pdf(recibo), "pdf", false);
                cobrado = recibo.neto();
                mejorRemuneracion = mejorRemuneracion.max(recibo.conceptos().stream()
                        .filter(c -> c.grupo() == GrupoConcepto.REMUNERATIVO).map(ReciboLeido.Concepto::importe)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            ingresos.cargar(ClaseIngreso.SUELDO, cobrado, "Sueldo " + mes, galicia.getId(), fechaCobro);
            if (mes.getMonthValue() == 6 || mes.getMonthValue() == 12) {
                ReciboLeido aguinaldo = PlanRecibosPrueba.aguinaldo(mes, mejorRemuneracion);
                recibos.guardar(aguinaldo, PlanRecibosPrueba.pdf(aguinaldo), "pdf", false);
                ingresos.cargar(ClaseIngreso.AGUINALDO, aguinaldo.neto(), "Aguinaldo " + mes, galicia.getId(),
                        fechaCobro);
            }
        }
    }

    /**
     * Unos gastos fijos para probar el checklist: el seguro sigue el flujo automático de siempre (puede quedar
     * pendiente de confirmar este mes); la luz tiene dos meses cargados y este mes queda "Falta"; Netflix ya está
     * cargado este mes.
     */
    private void cargarFijos(Map<String, Categoria> categorias, ar.gastito.catalogo.TarjetaCredito tarjeta) {
        LocalDateTime ahora = LocalDateTime.now(clock);

        fijos.alta(new GastoFijoService.NuevoFijo("Seguro de la moto", new BigDecimal("32900"),
                buscar(categorias, "Seguros"), null, null, tarjeta, 4, Frecuencia.MENSUAL, false, "federa8", true));
        fijos.generarCobros();

        VistaFijo luz = fijos.alta(new GastoFijoService.NuevoFijo("Luz", new BigDecimal("28000"),
                buscar(categorias, "Servicios (luz, gas, servicios sociales)"), null, null, null, 27,
                Frecuencia.MENSUAL, true, "edesur,factura de luz", false));
        fijos.cargarManual(luz.id(), new BigDecimal("26500"), ahora.minusMonths(2).withDayOfMonth(27),
                Origen.DASHBOARD, null);
        fijos.cargarManual(luz.id(), new BigDecimal("28900"), ahora.minusMonths(1).withDayOfMonth(27),
                Origen.DASHBOARD, null);
        // Este mes queda sin cargar a propósito: aparece como "Falta" en el checklist.

        VistaFijo netflix = fijos.alta(new GastoFijoService.NuevoFijo("Netflix", new BigDecimal("7999"),
                buscar(categorias, "Suscripciones"), null, null, tarjeta, 15, Frecuencia.MENSUAL, false,
                "netflix.com", false));
        fijos.cargarManual(netflix.id(), new BigDecimal("7999"), ahora.minusDays(2), Origen.DASHBOARD, null);
    }

    private static <T> Map<String, T> porNombre(List<T> elementos, Function<T, String> nombre) {
        return elementos.stream().collect(Collectors.toMap(nombre, Function.identity()));
    }

    private static <T> T buscar(Map<String, T> porNombre, String nombre) {
        T t = porNombre.get(nombre);
        if (t == null) {
            throw new IllegalStateException("No está en el catálogo: " + nombre);
        }
        return t;
    }
}
