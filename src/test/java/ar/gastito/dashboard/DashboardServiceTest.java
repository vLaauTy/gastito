package ar.gastito.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.catalogo.TarjetaCreditoRepository;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.UsoIa;
import ar.gastito.ia.UsoIaRepository;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/** Los números del dashboard con datos en H2. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DashboardService.class, IngresoService.class, MovimientoService.class, DashboardServiceTest.Reloj.class})
class DashboardServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Miércoles 21/10/2026 16:42 en Argentina. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 21, 16, 42);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);
    static final YearMonth SEPTIEMBRE = YearMonth.of(2026, 9);

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return reloj(AHORA);
        }
    }

    static Clock reloj(LocalDateTime enArgentina) {
        return Clock.fixed(enArgentina.atZone(ARGENTINA).toInstant(), ARGENTINA);
    }

    @Autowired
    DashboardService dashboard;

    @Autowired
    DashboardRepository consultas;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    IngresoService ingresos;

    @Autowired
    UsoIaRepository usoIa;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @Autowired
    CuentaRepository cuentas;

    @Autowired
    TarjetaCreditoRepository tarjetas;

    @Autowired
    JdbcTemplate jdbc;

    // --- Datos ---

    private Categoria categoria(String nombre) {
        return categorias.findByNombre(nombre).orElseThrow();
    }

    private MedioPago medio(String nombre) {
        return medios.findAll().stream().filter(m -> m.getNombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Cuenta cuenta(String nombre) {
        return cuentas.findAll().stream().filter(c -> c.getNombre().equals(nombre)).findFirst().orElseThrow();
    }

    private TarjetaCredito mastercard() {
        return tarjetas.findAll().get(0);
    }

    private Movimiento gasto(String monto, LocalDateTime fecha, String categoria, MedioPago medio, Cuenta cuenta,
            TarjetaCredito tarjeta) {
        Movimiento m = movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal(monto), "test",
                categoria(categoria), List.of(), medio, cuenta, tarjeta, fecha), Origen.TEXTO, "test");
        movimientos.confirmar(m.getId());
        return m;
    }

    private Movimiento gasto(String monto, LocalDateTime fecha) {
        return gasto(monto, fecha, "Kiosco", medio("Efectivo"), null, null);
    }

    private static LocalDateTime dia(int mes, int dia) {
        return LocalDateTime.of(2026, mes, dia, 12, 0);
    }

    /** Un movimiento confirmado de un tipo que no es gasto. */
    private void otroTipo(String tipo, String monto, LocalDateTime fecha) {
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en)
                VALUES (?, CAST(? AS NUMERIC(14, 2)), ?, 'TEXTO', 'CONFIRMADO', ?)""", fecha, monto, tipo, fecha);
    }

    private DashboardService conReloj(LocalDateTime enArgentina) {
        return new DashboardService(consultas, ingresos, usoIa, reloj(enArgentina));
    }

    // --- Qué cuenta ---

    @Test
    void soloCuentanLosGastosConfirmados() {
        gasto("1000", dia(10, 5));
        movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal("5000"), "pendiente",
                categoria("Kiosco"), List.of(), null, null, null, dia(10, 6)), Origen.TEXTO, "x");
        Movimiento cancelado = movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal("7000"),
                "cancelado", categoria("Kiosco"), List.of(), null, null, null, dia(10, 7)), Origen.TEXTO, "x");
        movimientos.cancelar(cancelado.getId());
        otroTipo("PAGO_TARJETA", "50000", dia(10, 8));
        otroTipo("ENTRE_CUENTAS", "20000", dia(10, 9));
        otroTipo("DEVOLUCION", "3000", dia(10, 10));
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), null, cuenta("Galicia").getId(),
                LocalDate.of(2026, 10, 1));

        DashboardService.Resumen r = dashboard.resumen(OCTUBRE);

        assertThat(r.gastado()).isEqualByComparingTo("1000");
        assertThat(r.cantidadGastos()).isEqualTo(1);
        assertThat(dashboard.porDia(OCTUBRE).dias()).extracting(DashboardService.Dia::total)
                .filteredOn(t -> t.signum() > 0).containsExactly(new BigDecimal("1000.00"));
        assertThat(dashboard.porCategoria(OCTUBRE)).hasSize(1);
        assertThat(dashboard.porBilletera(OCTUBRE)).extracting(DashboardService.Billetera::total)
                .containsExactly(new BigDecimal("1000.00"));
    }

    @Test
    void losLimitesDelMesSonEnHoraArgentina() {
        gasto("1", LocalDateTime.of(2026, 9, 30, 23, 59));
        gasto("10", LocalDateTime.of(2026, 10, 1, 0, 0));
        gasto("100", LocalDateTime.of(2026, 10, 31, 23, 59));
        gasto("1000", LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(dashboard.resumen(OCTUBRE).gastado()).isEqualByComparingTo("110");
    }

    @Test
    void elMesActualSaleDelRelojEnHoraArgentina() {
        // 01/11 01:30 UTC todavía es 31/10 22:30 en Argentina.
        Clock utcYaEsNoviembre = Clock.fixed(ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ZoneId.of("UTC")).toInstant(),
                ARGENTINA);
        DashboardService enElLimite = new DashboardService(consultas, ingresos, usoIa, utcYaEsNoviembre);

        DashboardService.Resumen r = enElLimite.resumen(OCTUBRE);

        assertThat(r.mesActual()).isTrue();
        assertThat(r.diasContados()).isEqualTo(31);
    }

    // --- Resumen ---

    @Test
    void comparaContraElMesAnteriorHastaElMismoDia() {
        gasto("1000", dia(9, 10));
        gasto("5000", dia(9, 25)); // después del día 21: no entra en la comparación
        gasto("1100", dia(10, 5));

        DashboardService.Comparacion c = dashboard.resumen(OCTUBRE).comparacion();

        assertThat(c.mes()).isEqualTo(SEPTIEMBRE);
        assertThat(c.total()).isEqualByComparingTo("1000");
        assertThat(c.hastaDia()).isEqualTo(21);
        assertThat(c.mesCompleto()).isFalse();
        assertThat(c.variacionPorcentaje()).isEqualTo(10);
        assertThat(c.direccion()).isEqualTo(DashboardService.Direccion.SUBE);
    }

    @Test
    void siElMesAnteriorEsMasCortoComparaHastaSuUltimoDia() {
        gasto("2000", LocalDateTime.of(2026, 2, 28, 20, 0));
        gasto("1000", LocalDateTime.of(2026, 3, 31, 10, 0));

        DashboardService.Comparacion c = conReloj(LocalDateTime.of(2026, 3, 31, 18, 0))
                .resumen(YearMonth.of(2026, 3)).comparacion();

        assertThat(c.hastaDia()).isEqualTo(28);
        assertThat(c.total()).isEqualByComparingTo("2000");
        assertThat(c.variacionPorcentaje()).isEqualTo(-50);
        assertThat(c.direccion()).isEqualTo(DashboardService.Direccion.BAJA);
    }

    @Test
    void unMesQueYaTerminoComparaLosDosMesesCompletos() {
        gasto("1000", dia(8, 31));
        gasto("1000", dia(9, 30));

        DashboardService.Resumen r = dashboard.resumen(SEPTIEMBRE);

        assertThat(r.mesActual()).isFalse();
        assertThat(r.diasContados()).isEqualTo(30);
        assertThat(r.comparacion().hastaDia()).isEqualTo(31);
        assertThat(r.comparacion().mesCompleto()).isTrue();
        assertThat(r.comparacion().direccion()).isEqualTo(DashboardService.Direccion.IGUAL);
        assertThat(r.comparacion().variacionPorcentaje()).isZero();
    }

    @Test
    void sinGastosEnElMesAnteriorNoHayVariacion() {
        gasto("1000", dia(10, 5));

        DashboardService.Comparacion c = dashboard.resumen(OCTUBRE).comparacion();

        assertThat(c.variacionPorcentaje()).isNull();
        assertThat(c.direccion()).isEqualTo(DashboardService.Direccion.SIN_DATOS);
    }

    @Test
    void elPromedioDiarioEsSobreLosDiasQuePasaron() {
        gasto("1000", dia(10, 5));
        gasto("1100", dia(10, 21));

        DashboardService.Resumen r = dashboard.resumen(OCTUBRE);

        assertThat(r.diasContados()).isEqualTo(21);
        assertThat(r.promedioDiario()).isEqualByComparingTo("100.00");
        assertThat(dashboard.resumen(SEPTIEMBRE).promedioDiario()).isEqualByComparingTo("0");
    }

    @Test
    void elDisponibleEsLoCobradoMenosLoGastado() {
        gasto("100000", dia(10, 5));
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), null, cuenta("Galicia").getId(),
                LocalDate.of(2026, 10, 1));
        ingresos.cargar(ClaseIngreso.EXTRA, new BigDecimal("20000"), "Arreglo de PC", null, LocalDate.of(2026, 10, 12));

        DashboardService.Resumen r = dashboard.resumen(OCTUBRE);

        assertThat(r.ingresos().total()).isEqualByComparingTo("400000");
        assertThat(r.ingresos().sueldo()).isEqualByComparingTo("380000");
        assertThat(r.ingresos().extras()).isEqualByComparingTo("20000");
        assertThat(r.disponible().monto()).isEqualByComparingTo("300000");
        assertThat(r.disponible().porcentajeGastado()).isEqualTo(25);
    }

    @Test
    void sinIngresosNoHayDisponible() {
        gasto("1000", dia(10, 5));

        assertThat(dashboard.resumen(OCTUBRE).disponible()).isNull();
    }

    // --- Por día y por categoría ---

    @Test
    void porDiaTraeTodosLosDiasConCerosElMaximoYLosFuturos() {
        gasto("500", dia(10, 3));
        gasto("300", dia(10, 3));
        gasto("2000", dia(10, 10));

        DashboardService.PorDia p = dashboard.porDia(OCTUBRE);

        assertThat(p.dias()).hasSize(31);
        assertThat(p.dias().get(2).total()).isEqualByComparingTo("800");
        assertThat(p.dias().get(0).total()).isEqualByComparingTo("0");
        assertThat(p.diaMaximo()).isEqualTo(10);
        assertThat(p.promedio()).isEqualByComparingTo("133.33"); // 2800 / 21
        assertThat(p.dias().get(20).futuro()).isFalse();
        assertThat(p.dias().get(21).futuro()).isTrue();
        assertThat(dashboard.porDia(SEPTIEMBRE).diaMaximo()).isNull();
    }

    @Test
    void porCategoriaDeMayorAMenorConPorcentajes() {
        gasto("3000", dia(10, 3), "Supermercado", medio("Efectivo"), null, null);
        gasto("1000", dia(10, 4), "Kiosco", medio("Efectivo"), null, null);
        gasto("500", dia(10, 5), "Kiosco", medio("Efectivo"), null, null);

        assertThat(dashboard.porCategoria(OCTUBRE))
                .extracting(DashboardService.CategoriaDelMes::nombre, c -> c.total().toPlainString(),
                        DashboardService.CategoriaDelMes::cantidad, c -> c.porcentajeDelMaximo().toPlainString(),
                        c -> c.porcentajeDelTotal().toPlainString())
                .containsExactly(
                        tuple("Supermercado", "3000.00", 1L, "100.0", "66.7"),
                        tuple("Kiosco", "1500.00", 2L, "50.0", "33.3"));
    }

    // --- Billeteras ---

    @Test
    void lasBilleterasSalenDeLaTarjetaLaCuentaOElEfectivo() {
        gasto("1000", dia(10, 1), "Kiosco", medio("Efectivo"), null, null);
        gasto("2000", dia(10, 2), "Kiosco", medio("Débito"), cuenta("Galicia"), null);
        gasto("500", dia(10, 3), "Kiosco", medio("QR"), cuenta("Galicia"), null);
        gasto("3000", dia(10, 4), "Kiosco", medio("QR"), cuenta("Mercado Pago"), null);
        gasto("4000", dia(10, 5), "Kiosco", medio("Tarjeta de crédito"), null, mastercard());
        gasto("300", dia(10, 6), "Kiosco", null, null, null); // sin medio de pago
        gasto("200", dia(10, 7), "Kiosco", medio("Débito"), null, null); // débito sin cuenta

        assertThat(dashboard.porBilletera(OCTUBRE))
                .extracting(DashboardService.Billetera::nombre, DashboardService.Billetera::tipo,
                        b -> b.total().toPlainString(), DashboardService.Billetera::cantidad,
                        DashboardService.Billetera::color)
                .containsExactly(
                        tuple("Efectivo", DashboardService.TipoBilletera.EFECTIVO, "1000.00", 1L, "#8A968E"),
                        tuple("Mercado Pago", DashboardService.TipoBilletera.CUENTA, "3000.00", 1L, "#2457C5"),
                        tuple("Galicia", DashboardService.TipoBilletera.CUENTA, "2500.00", 2L, "#1F7A4D"),
                        tuple("MasterCard Gold", DashboardService.TipoBilletera.TARJETA, "4000.00", 1L, "#B4531F"),
                        tuple("Sin especificar", DashboardService.TipoBilletera.SIN_ESPECIFICAR, "500.00", 2L,
                                "#D5DBD2"));
        assertThat(dashboard.porBilletera(OCTUBRE)).extracting(b -> b.porcentaje().toPlainString())
                .containsExactly("9.1", "27.3", "22.7", "36.4", "4.5");
    }

    @Test
    void unaCuentaNuevaApareceSolaConUnColorDeReserva() {
        Cuenta brubank = cuentas.save(new Cuenta("Brubank", "brubank"));
        gasto("1000", dia(10, 1), "Kiosco", medio("Débito"), brubank, null);

        DashboardService.Billetera b = dashboard.porBilletera(OCTUBRE).get(0);

        assertThat(b.nombre()).isEqualTo("Brubank");
        assertThat(b.tipo()).isEqualTo(DashboardService.TipoBilletera.CUENTA);
        assertThat(DashboardService.COLORES_DE_RESERVA).contains(b.color());
    }

    // --- Ingresos y costos ---

    @Test
    void ingresosDelMesConLaBilleteraDeCadaUno() {
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), "Sueldo de septiembre",
                cuenta("Galicia").getId(), LocalDate.of(2026, 10, 1));
        ingresos.cargar(ClaseIngreso.AGUINALDO, new BigDecimal("190000"), null, cuenta("Galicia").getId(),
                LocalDate.of(2026, 10, 2));
        ingresos.cargar(ClaseIngreso.EXTRA, new BigDecimal("25000"), "Arreglo de PC", null, LocalDate.of(2026, 10, 12));

        DashboardService.IngresosDelMes r = dashboard.ingresosDelMes(OCTUBRE);

        assertThat(r.totales().aguinaldo()).isEqualByComparingTo("190000");
        assertThat(r.ingresos()).extracting(DashboardService.IngresoDelMes::claseNombre,
                DashboardService.IngresoDelMes::billetera, i -> i.monto().toPlainString())
                .containsExactly(tuple("Sueldo", "Galicia", "380000.00"), tuple("Aguinaldo", "Galicia", "190000.00"),
                        tuple("Extra", "Efectivo", "25000.00"));
    }

    @Test
    void ingresosDeLosUltimosMesesConElAnchoDeLaBarra() {
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("200000"), null, null, LocalDate.of(2026, 9, 1));
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("400000"), null, null, LocalDate.of(2026, 10, 1));

        assertThat(dashboard.ingresosPorMes(OCTUBRE, 6))
                .extracting(DashboardService.IngresoMensual::mes, m -> m.porcentajeDelMaximo().toPlainString())
                .containsExactly(tuple(YearMonth.of(2026, 5), "0.0"), tuple(YearMonth.of(2026, 6), "0.0"),
                        tuple(YearMonth.of(2026, 7), "0.0"), tuple(YearMonth.of(2026, 8), "0.0"),
                        tuple(SEPTIEMBRE, "50.0"), tuple(OCTUBRE, "100.0"));
    }

    @Test
    void costosDeLaIaDelMesPorProposito() {
        usoIa.save(new UsoIa(dia(10, 5), "claude-haiku-5-5", 1000, 100, PropositoIa.TEXTO, new BigDecimal("0.00015")));
        usoIa.save(new UsoIa(dia(10, 6), "claude-haiku-5-5", 1000, 100, PropositoIa.TEXTO, new BigDecimal("0.00015")));
        usoIa.save(new UsoIa(dia(10, 7), "claude-haiku-5-5", 2000, 200, PropositoIa.AUDIO, new BigDecimal("0.0003")));
        usoIa.save(new UsoIa(dia(9, 7), "claude-haiku-5-5", 9000, 900, PropositoIa.TEXTO, new BigDecimal("0.5")));

        DashboardService.CostosIa c = dashboard.costosIa(OCTUBRE);

        assertThat(c.llamadas()).isEqualTo(3);
        assertThat(c.costoUsd()).isEqualByComparingTo("0.0006");
        assertThat(c.tokensEntrada()).isEqualTo(4000);
        assertThat(c.porProposito()).extracting(DashboardService.CostoPorProposito::proposito,
                DashboardService.CostoPorProposito::llamadas)
                .containsExactly(tuple("AUDIO", 1L), tuple("TEXTO", 2L));
    }
}
