package ar.gastito.resumen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.CalculadoraCostoIa;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * La semana del lunes 12 al domingo 18 de octubre de 2026 contra la anterior (5 al 11), con los números
 * pensados para verificarlos a mano.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CalculoResumen.class, MovimientoService.class, IngresoService.class, RegistroUsoIa.class,
        CalculadoraCostoIa.class, CalculoResumenTest.Config.class})
class CalculoResumenTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate LUNES = LocalDate.of(2026, 10, 12);
    static final String SEVEN_UP = "7791813777021";

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 10, 20, 10, 0).atZone(ARGENTINA).toInstant(), ARGENTINA);
        }

        @Bean
        GastitoProperties properties() {
            return new GastitoProperties(ARGENTINA, null,
                    new GastitoProperties.Ia("x", null, "claude-haiku-5-5", 1024, Map.of("claude-haiku-5-5",
                            new GastitoProperties.Precio(new BigDecimal("0.10"), new BigDecimal("0.50")))),
                    null, null,
                    new GastitoProperties.Resumenes(LocalTime.of(21, 0), LocalTime.of(9, 0), "claude-haiku-5-5",
                            "claude-haiku-5-5", 1024, new BigDecimal("3000"), List.of("Kiosco", "Comida afuera / delivery"),
                            new GastitoProperties.Minimo(5, 3), new GastitoProperties.Minimo(15, 7)),
                    null);
        }
    }

    @Autowired
    CalculoResumen calculo;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    IngresoService ingresos;

    @Autowired
    RegistroUsoIa usoIa;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @BeforeEach
    void cargarGastos() {
        // Semana anterior: 1.000 de kiosco y 30.000 de súper.
        gasto("Kiosco", "1000", LocalDate.of(2026, 10, 6), true);
        gasto("Supermercado", "30000", LocalDate.of(2026, 10, 8), true);
        // Esta semana. Hormiga: 1.500, 2.999, 2.500 (comida) y 1.200. El de 3.000 justo no es hormiga.
        gasto("Kiosco", "1500", LUNES, true);
        gasto("Kiosco", "2999", LUNES.plusDays(1), true);
        gasto("Kiosco", "3000", LUNES.plusDays(1), false); // sin medio de pago
        gasto("Comida afuera / delivery", "2500", LUNES.plusDays(2), true);
        ticket("Supermercado", "20000", LUNES.plusDays(2), List.of(
                new ItemTicket("Seven Up lata 354 ml", null, SEVEN_UP, new BigDecimal("2"), new BigDecimal("2250"),
                        new BigDecimal("4500")),
                new ItemTicket("Galletitas", new BigDecimal("1"), new BigDecimal("3000"), new BigDecimal("3000")),
                new ItemTicket("20% ClubDía", null, null, new BigDecimal("-500"))));
        ticket("Kiosco", "1200", LUNES.plusDays(4), List.of(
                new ItemTicket("Seven Up", null, SEVEN_UP, new BigDecimal("1"), new BigDecimal("1200"),
                        new BigDecimal("1200"))));
        // Un gasto pendiente no cuenta.
        movimientos.crearPendiente(interpretado("Kiosco", "99999", LUNES), Origen.TEXTO, "x");
    }

    private DatosResumen semana() {
        return calculo.calcular(PeriodoResumen.semanaDe(LUNES), LocalDate.of(2026, 10, 20));
    }

    @Test
    void totalCantidadYComparacionContraLaSemanaAnterior() {
        DatosResumen d = semana();

        assertThat(d.periodo().parcial()).isFalse();
        assertThat(d.periodo().dias()).isEqualTo(7);
        assertThat(d.totalGastado()).isEqualByComparingTo("31199");
        assertThat(d.cantidadGastos()).isEqualTo(6);
        assertThat(d.comparacion().total()).isEqualByComparingTo("31000");
        assertThat(d.comparacion().variacionPorcentaje()).isEqualTo(1); // 199 / 31.000 = 0,64%
    }

    @Test
    void unResumenParcialSeComparaHastaElMismoDia() {
        DatosResumen d = calculo.calcular(PeriodoResumen.semanaDe(LUNES), LUNES.plusDays(2)); // miércoles

        assertThat(d.periodo().parcial()).isTrue();
        assertThat(d.periodo().hasta()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(d.periodo().fin()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(d.totalGastado()).isEqualByComparingTo("29999");
        // La semana anterior, de lunes a miércoles: solo el kiosco de 1.000.
        assertThat(d.comparacion().hasta()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(d.comparacion().total()).isEqualByComparingTo("1000");
        // 5 gastos en 3 días: justo el mínimo, así que hay escenarios.
        assertThat(d.pocosDatos()).isFalse();
        assertThat(d.escenariosAhorro()).isNotEmpty();
    }

    @Test
    void porCategoriaDeMayorAMenorConSuVariacion() {
        assertThat(semana().porCategoria())
                .extracting(DatosResumen.Categoria::nombre, c -> c.total().toPlainString(), DatosResumen.Categoria::cantidad,
                        DatosResumen.Categoria::variacionPorcentaje)
                .containsExactly(
                        tuple("Supermercado", "20000.00", 1, -33),
                        tuple("Kiosco", "8699.00", 4, 770),
                        tuple("Comida afuera / delivery", "2500.00", 1, null));
    }

    @Test
    void gastosHormigaPorDebajoDelUmbralYSoloEnSusCategorias() {
        DatosResumen.Hormiga h = semana().hormiga();

        assertThat(h.cantidad()).isEqualTo(4);
        assertThat(h.total()).isEqualByComparingTo("8199");
        assertThat(h.promedio()).isEqualByComparingTo("2049.75");
        assertThat(h.porSemana()).isEqualByComparingTo("4.0");
    }

    @Test
    void diasSinGastosYElMasGrande() {
        DatosResumen d = semana();

        assertThat(d.diasSinGastos()).isEqualTo(3); // jueves 15, sábado 17 y domingo 18
        assertThat(d.gastoMasGrande().monto()).isEqualByComparingTo("20000");
        assertThat(d.gastoMasGrande().categoria()).isEqualTo("Supermercado");
    }

    @Test
    void productosMasCompradosJuntandoElMismoCodigoDeBarras() {
        DatosResumen.Productos p = semana().productos();

        assertThat(p.porUnidades()).first().satisfies(prod -> {
            assertThat(prod.nombre()).isEqualTo("Seven Up"); // el nombre del renglón más reciente
            assertThat(prod.unidades()).isEqualByComparingTo("3");
            assertThat(prod.total()).isEqualByComparingTo("5700");
        });
        assertThat(p.porPlata()).extracting(DatosResumen.Producto::nombre).containsExactly("Seven Up", "Galletitas");
    }

    @Test
    void escenariosDeAhorroConLosNumerosHechos() {
        assertThat(semana().escenariosAhorro())
                .extracting(DatosResumen.Escenario::tipo, DatosResumen.Escenario::categoria,
                        e -> e.ahorroPeriodo().toPlainString(), e -> e.ahorroMensual().toPlainString())
                .containsExactly(
                        // (4 − 3) compras × 1 semana × $2.049,75; al mes: 2.049,75 × 30,4 / 7 = 8.901,7
                        tuple("HORMIGA", null, "2050.00", "8900.00"),
                        // Kiosco: 8.699 − 1.000 = 7.699; al mes: 33.435,5
                        tuple("CATEGORIA_QUE_MAS_SUBIO", "Kiosco", "7699.00", "33400.00"),
                        // Supermercado: 10% de 20.000; al mes: 8.685,7
                        tuple("CATEGORIA_MAS_GRANDE", "Supermercado", "2000.00", "8700.00"));
    }

    @Test
    void conPocosDatosNoHayEscenarios() {
        // La semana anterior: 2 gastos en 2 días.
        DatosResumen d = calculo.calcular(PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 6)), LUNES);

        assertThat(d.cantidadGastos()).isEqualTo(2);
        assertThat(d.pocosDatos()).isTrue();
        assertThat(d.escenariosAhorro()).isEmpty();
    }

    @Test
    void unaCategoriaSinSemanasAnterioresNoSeLlevaAlMes() {
        gasto("Ropa", "50000", LUNES.plusDays(3), true);

        assertThat(semana().escenariosAhorro())
                .extracting(DatosResumen.Escenario::tipo, DatosResumen.Escenario::categoria,
                        e -> e.ahorroPeriodo().toPlainString(), e -> String.valueOf(e.ahorroMensual()))
                .containsExactly(
                        // Kiosco tiene gastos de la semana anterior: se lleva al mes.
                        tuple("HORMIGA", null, "2050.00", "8900.00"),
                        // Ropa es la primera semana: el ahorro queda por semana.
                        tuple("CATEGORIA_QUE_MAS_SUBIO", "Ropa", "50000.00", "null"),
                        tuple("CATEGORIA_MAS_GRANDE", "Ropa", "5000.00", "null"));
    }

    @Test
    void lasNotasAlPieSoloSiHayJuntadas() {
        Movimiento comun = Movimiento.pendiente(interpretado("Salidas", "9000", LUNES), Origen.TEXTO, "x",
                LUNES.atStartOfDay());
        Movimiento juntada = Movimiento.pendiente(interpretado("Salidas", "12000", LUNES), Origen.TEXTO, "x",
                LUNES.atStartOfDay());
        ReflectionTestUtils.setField(juntada, "juntada", true);

        assertThat(CalculoResumen.notas(List.of(comun))).isEmpty();
        assertThat(CalculoResumen.notas(List.of(comun, juntada))).containsExactly(
                "1 gasto es de una juntada: lo que te devuelvan todavía no se descuenta del total.");
    }

    @Test
    void sinJuntadasNoHayNotasAlPie() {
        assertThat(semana().notas()).isEmpty();
        assertThat(semana().pocosDatos()).isFalse();
        assertThat(semana().sinEspecificar().cantidad()).isEqualTo(1);
        assertThat(semana().sinEspecificar().total()).isEqualByComparingTo("3000");
        assertThat(semana().ingresos()).isNull(); // solo en el mensual
    }

    @Test
    void elMensualTraeIngresosDisponibleYCostoDeLaIa() {
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), null, null, LocalDate.of(2026, 10, 1));
        ingresos.cargar(ClaseIngreso.AGUINALDO, new BigDecimal("190000"), null, null, LocalDate.of(2026, 10, 2));
        usoIa.registrar("claude-haiku-5-5", 10_000, 1_000, PropositoIa.TEXTO); // 0,001 + 0,0005

        DatosResumen d = calculo.calcular(PeriodoResumen.mesDe(LUNES), LocalDate.of(2026, 11, 1));

        assertThat(d.periodo().tipo()).isEqualTo("MENSUAL");
        assertThat(d.periodo().parcial()).isFalse();
        assertThat(d.totalGastado()).isEqualByComparingTo("62199"); // las dos semanas
        assertThat(d.ingresos().sueldo()).isEqualByComparingTo("380000");
        assertThat(d.ingresos().aguinaldo()).isEqualByComparingTo("190000");
        assertThat(d.ingresos().total()).isEqualByComparingTo("570000");
        assertThat(d.ingresos().disponible()).isEqualByComparingTo("507801");
        assertThat(d.ingresos().porcentajeGastado()).isEqualTo(11); // 62.199 / 570.000 = 10,9%
        assertThat(d.costoIaUsd()).isEqualByComparingTo("0.0015");
    }

    // --- Datos ---

    private MovimientoInterpretado interpretado(String categoria, String monto, LocalDate dia) {
        return MovimientoInterpretado.conCategoria(new BigDecimal(monto), categoria,
                categorias.findByNombre(categoria).orElseThrow(), efectivo(), null, null, dia.atTime(12, 30));
    }

    private void gasto(String categoria, String monto, LocalDate dia, boolean conMedio) {
        MovimientoInterpretado i = interpretado(categoria, monto, dia);
        if (!conMedio) {
            i = new MovimientoInterpretado(i.monto(), i.descripcion(), i.categoria(), List.of(), null, null, null,
                    i.fechaHora());
        }
        Movimiento m = movimientos.crearPendiente(i, Origen.TEXTO, "x");
        movimientos.confirmar(m.getId());
    }

    private void ticket(String categoria, String monto, LocalDate dia, List<ItemTicket> items) {
        Movimiento m = movimientos.crearPendienteDeTicket(interpretado(categoria, monto, dia),
                DatosTicket.de(categoria, items), null);
        movimientos.confirmar(m.getId());
    }

    private MedioPago efectivo() {
        return medios.findAll().stream().filter(MedioPago::isEfectivo).findFirst().orElseThrow();
    }
}
