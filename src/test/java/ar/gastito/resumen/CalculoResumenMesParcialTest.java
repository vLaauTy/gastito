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
 * El registro empezó el sábado 31/10/2026: octubre (y la semana del 26/10) quedan incompletos. El mínimo para el
 * consejo está bajo, así que lo único que lo saca es que el registro empezó a mitad del período.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CalculoResumen.class, MovimientoService.class, IngresoService.class, RegistroUsoIa.class, InicioRegistro.class,
        CalculadoraCostoIa.class, CalculoResumenMesParcialTest.Config.class})
class CalculoResumenMesParcialTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate PRIMER_GASTO = LocalDate.of(2026, 10, 31);

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 11, 1, 9, 0).atZone(ARGENTINA).toInstant(), ARGENTINA);
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
        gasto("Supermercado", "45000");
        gasto("Kiosco", "1200");
    }

    @Test
    void elMesDiceDesdeCuandoHayRegistroYNoCompraNiAconseja() {
        DatosResumen octubre = calculo.calcular(PeriodoResumen.mesDe(LocalDate.of(2026, 10, 1)),
                LocalDate.of(2026, 11, 1));

        assertThat(octubre.periodo().registroDesde()).isEqualTo(PRIMER_GASTO);
        assertThat(octubre.totalGastado()).isEqualByComparingTo("46200");
        assertThat(octubre.comparacion().conDatos()).isFalse();
        assertThat(octubre.escenariosAhorro()).isEmpty();
        assertThat(octubre.pocosDatos()).isFalse();
        assertThat(octubre.conConsejo()).isFalse();
        assertThat(octubre.sinConsejo()).startsWith("Registro desde el 31/10");
    }

    @Test
    void laSemanaDelPrimerGastoTambienEsParcial() {
        DatosResumen semana = calculo.calcular(PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 26)),
                LocalDate.of(2026, 11, 1));

        assertThat(semana.periodo().registroDesde()).isEqualTo(PRIMER_GASTO);
        assertThat(semana.conConsejo()).isFalse();
    }

    @Test
    void unMesQueEmpiezaDespuesDelPrimerGastoEsCompleto() {
        DatosResumen noviembre = calculo.calcular(PeriodoResumen.mesDe(LocalDate.of(2026, 11, 1)),
                LocalDate.of(2026, 11, 1));

        assertThat(noviembre.periodo().registroDesde()).isNull();
        assertThat(noviembre.sinConsejo()).isEqualTo(DatosResumen.POCOS_DATOS);
    }

    private void gasto(String categoria, String monto) {
        MedioPago efectivo = medios.findAll().stream().filter(MedioPago::isEfectivo).findFirst().orElseThrow();
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto), categoria,
                categorias.findByNombre(categoria).orElseThrow(), efectivo, null, null, PRIMER_GASTO.atTime(12, 30)),
                Origen.TEXTO, "x");
        movimientos.confirmar(m.getId());
    }
}
