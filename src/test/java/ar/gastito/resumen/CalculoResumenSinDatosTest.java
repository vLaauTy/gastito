package ar.gastito.resumen;

import static org.assertj.core.api.Assertions.assertThat;

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
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.InicioRegistro;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * Octubre de 2026 es el primer mes registrado: 3 gastos, y Supermercado con uno solo. Septiembre no tiene datos (no
 * es que se gastó $0). El mínimo para el consejo está bajo para que los escenarios se calculen igual.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CalculoResumen.class, MovimientoService.class, IngresoService.class, RegistroUsoIa.class, InicioRegistro.class,
        CalculadoraCostoIa.class, CalculoResumenSinDatosTest.Config.class})
class CalculoResumenSinDatosTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate OCTUBRE = LocalDate.of(2026, 10, 1);

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 11, 2, 10, 0).atZone(ARGENTINA).toInstant(), ARGENTINA);
        }

        @Bean
        GastitoProperties properties() {
            return new GastitoProperties(ARGENTINA, null,
                    new GastitoProperties.Ia("x", null, "claude-haiku-5-5", 1024, Map.of()), null, null,
                    new GastitoProperties.Resumenes(LocalTime.of(21, 0), LocalTime.of(9, 0), "claude-haiku-5-5",
                            "claude-haiku-5-5", 1024, new BigDecimal("3000"), List.of("Kiosco"),
                            new GastitoProperties.Minimo(1, 1), new GastitoProperties.Minimo(1, 1)),
                    null);
        }
    }

    @Autowired
    CalculoResumen calculo;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @BeforeEach
    void cargarGastos() {
        gasto("Supermercado", "4500", OCTUBRE.plusDays(4));
        gasto("Kiosco", "1200", OCTUBRE.plusDays(9));
        gasto("Kiosco", "800", OCTUBRE.plusDays(19));
    }

    private DatosResumen octubre() {
        return calculo.calcular(PeriodoResumen.mesDe(OCTUBRE), LocalDate.of(2026, 11, 1));
    }

    @Test
    void septiembreNoTieneDatosNoEsCero() {
        DatosResumen d = octubre();

        assertThat(d.cantidadGastos()).isEqualTo(3);
        assertThat(d.comparacion().conDatos()).isFalse();
        assertThat(d.comparacion().total()).isNull();
        assertThat(d.comparacion().variacionPorcentaje()).isNull();
        assertThat(d.porCategoria()).allSatisfy(c -> {
            assertThat(c.totalAnterior()).isNull();
            assertThat(c.variacionPorcentaje()).isNull();
        });
    }

    @Test
    void sinDatosDelAnteriorNoHayEscenarioDeVolverAEseNivel() {
        assertThat(octubre().escenariosAhorro()).extracting(DatosResumen.Escenario::tipo)
                .doesNotContain("CATEGORIA_QUE_MAS_SUBIO")
                .containsExactly("CATEGORIA_MAS_GRANDE");
    }

    @Test
    void ningunEscenarioPrometeMasDeLoGastadoEnLaCategoria() {
        DatosResumen d = octubre();

        // Supermercado, con un solo gasto de $4.500: el 10% es $450.
        assertThat(d.escenariosAhorro()).singleElement().satisfies(e -> {
            assertThat(e.categoria()).isEqualTo("Supermercado");
            assertThat(e.ahorroPeriodo()).isEqualByComparingTo("450");
        });
        assertThat(d.escenariosAhorro()).allSatisfy(e -> {
            BigDecimal gastado = d.porCategoria().stream().filter(c -> c.nombre().equals(e.categoria()))
                    .findFirst().orElseThrow().total();
            assertThat(e.ahorroPeriodo()).isLessThanOrEqualTo(gastado);
        });
    }

    @Test
    void unEscenarioQuePrometeMasDeLoGastadoSeSaca() {
        List<DatosResumen.Categoria> cats = List.of(new DatosResumen.Categoria("Supermercado", "🛒",
                new BigDecimal("4500.00"), 1, null, null));
        DatosResumen.Hormiga hormiga = new DatosResumen.Hormiga(new BigDecimal("3000.00"), List.of("Kiosco"), 2,
                new BigDecimal("2000.00"), new BigDecimal("1000.00"), new BigDecimal("0.5"));

        assertThat(CalculoResumen.dentroDeLoGastado(new DatosResumen.Escenario("CATEGORIA_QUE_MAS_SUBIO", "x",
                "Supermercado", new BigDecimal("4500.00"), BigDecimal.ZERO, new BigDecimal("4600.00"), null), cats,
                hormiga, 31)).isFalse();
        assertThat(CalculoResumen.dentroDeLoGastado(new DatosResumen.Escenario("HORMIGA", "x", null,
                new BigDecimal("4"), new BigDecimal("3"), new BigDecimal("2500.00"), null), cats, hormiga, 31))
                .isFalse();
        assertThat(CalculoResumen.dentroDeLoGastado(new DatosResumen.Escenario("CATEGORIA_MAS_GRANDE", "x",
                "Supermercado", new BigDecimal("4500.00"), new BigDecimal("4050.00"), new BigDecimal("450.00"),
                new BigDecimal("500.00")), cats, hormiga, 31)).isTrue();
    }

    private void gasto(String categoria, String monto, LocalDate dia) {
        MedioPago efectivo = medios.findAll().stream().filter(MedioPago::isEfectivo).findFirst().orElseThrow();
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto), categoria,
                categorias.findByNombre(categoria).orElseThrow(), efectivo, null, null, dia.atTime(12, 30)),
                Origen.TEXTO, "x");
        movimientos.confirmar(m.getId());
    }
}
