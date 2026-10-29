package ar.gastito.fijo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.fijo.GastoFijoService.EstadoChecklist;
import ar.gastito.fijo.GastoFijoService.ItemChecklist;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.RelojDePrueba;

/** "Pagado" con el monto aproximado y la corrección con el real. Hoy es el domingo 25/10/2026 a las 10:00. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({GastoFijoService.class, PagoFijoService.class, PagoFijoServiceTest.Config.class})
class PagoFijoServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 25, 10, 0);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    @TestConfiguration
    static class Config {

        @Bean
        RelojDePrueba clock() {
            return new RelojDePrueba(HOY, ARGENTINA);
        }
    }

    @Autowired
    GastoFijoService fijos;

    @Autowired
    PagoFijoService pagos;

    @Autowired
    MovimientoRepository movimientos;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    RelojDePrueba reloj;

    @BeforeEach
    void volverAHoy() {
        reloj.ir(HOY);
    }

    private Categoria servicios() {
        return categorias.findByNombre("Servicios (luz, gas, servicios sociales)").orElseThrow();
    }

    /** Del checklist (no propone monto). Empieza en octubre si el día no pasó. */
    private VistaFijo fijo(String nombre, String monto, boolean variable, int dia) {
        return fijos.alta(new GastoFijoService.NuevoFijo(nombre, new BigDecimal(monto), servicios(), null, null,
                null, dia, Frecuencia.MENSUAL, variable, null, false));
    }

    private ItemChecklist item(VistaFijo f, YearMonth mes) {
        return fijos.checklist(mes).stream().filter(i -> i.fijoId().equals(f.id())).findFirst().orElseThrow();
    }

    private List<Movimiento> delPeriodo(VistaFijo f, YearMonth mes) {
        return movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(f.id(), mes.atDay(1));
    }

    @Test
    void unFijoDeMontoFijoSeTildaConElEsperadoYQuedaEstimado() {
        VistaFijo internet = fijo("Internet", "25000", false, 28);
        assertThat(item(internet, OCTUBRE).aproximado()).isEqualByComparingTo("25000");

        Movimiento m = pagos.pagado(internet.id(), OCTUBRE, Origen.DASHBOARD);

        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(m.isMontoEstimado()).isTrue();
        assertThat(m.getMonto()).isEqualByComparingTo("25000");
        assertThat(m.getFechaHora().toLocalDate()).isEqualTo(HOY.toLocalDate());
        ItemChecklist item = item(internet, OCTUBRE);
        assertThat(item.estado()).isEqualTo(EstadoChecklist.CARGADO);
        assertThat(item.estimado()).isTrue();
        assertThat(item.movimientoId()).isEqualTo(m.getId());
        assertThatThrownBy(() -> pagos.pagado(internet.id(), OCTUBRE, Origen.DASHBOARD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ya cargaste Internet");
    }

    @Test
    void unVariableSeTildaConElDelMesAnteriorOSinoConElEsperado() {
        VistaFijo luz = fijo("Luz", "80000", true, 28);
        assertThat(item(luz, OCTUBRE).aproximado()).isEqualByComparingTo("80000");
        fijos.cargarManual(luz.id(), new BigDecimal("85000"), LocalDateTime.of(2026, 9, 27, 10, 0), Origen.TEXTO,
                null);
        assertThat(item(luz, OCTUBRE).aproximado()).isEqualByComparingTo("85000");

        Movimiento m = pagos.pagado(luz.id(), OCTUBRE, Origen.DASHBOARD);

        assertThat(m.getMonto()).isEqualByComparingTo("85000");
        assertThat(m.isMontoEstimado()).isTrue();
    }

    @Test
    void siElCobroAutomaticoEsperabaRespuestaSeUsaEseMismo() {
        VistaFijo seguro = fijos.alta(new GastoFijoService.NuevoFijo("Seguro", new BigDecimal("32900"),
                servicios(), null, null, null, 4, Frecuencia.MENSUAL, false));
        reloj.ir(LocalDateTime.of(2026, 11, 4, 10, 0));
        Movimiento pendiente = fijos.generarCobros().get(0);

        Movimiento m = pagos.pagado(seguro.id(), YearMonth.of(2026, 11), Origen.TEXTO);

        assertThat(m.getId()).isEqualTo(pendiente.getId());
        assertThat(m.isMontoEstimado()).isTrue();
        assertThat(delPeriodo(seguro, YearMonth.of(2026, 11))).hasSize(1);
    }

    @Test
    void corregirElMontoCambiaElMismoGastoYDejaDeSerEstimado() {
        VistaFijo luz = fijo("Luz", "85000", false, 28);
        Movimiento estimado = pagos.pagado(luz.id(), OCTUBRE, Origen.DASHBOARD);

        Movimiento corregido = pagos.corregirMonto(estimado.getId(), new BigDecimal("87500"));

        assertThat(corregido.getId()).isEqualTo(estimado.getId());
        assertThat(corregido.getMonto()).isEqualByComparingTo("87500");
        assertThat(corregido.isMontoEstimado()).isFalse();
        assertThat(delPeriodo(luz, OCTUBRE)).hasSize(1);
        assertThat(item(luz, OCTUBRE).estimado()).isFalse();
        assertThat(item(luz, OCTUBRE).monto()).isEqualByComparingTo("87500");
        // También sirve sobre uno que ya tenía el monto real.
        assertThat(pagos.corregirMonto(estimado.getId(), new BigDecimal("88000")).getMonto())
                .isEqualByComparingTo("88000");
        assertThatThrownBy(() -> pagos.corregirMonto(estimado.getId(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Movimiento gastoDelBot(VistaFijo f, String monto) {
        Movimiento m = movimientos.save(Movimiento.pendiente(MovimientoInterpretado.conCategoria(
                new BigDecimal(monto), "Luz", servicios(), null, null, null, HOY), Origen.TEXTO,
                "de luz pagué " + monto, HOY));
        m.vincularFijo(f.id(), OCTUBRE.atDay(1));
        return m;
    }

    @Test
    void elBotActualizaElEstimadoSinCrearOtroGasto() {
        VistaFijo luz = fijo("Luz", "85000", false, 28);
        Movimiento estimado = pagos.pagado(luz.id(), OCTUBRE, Origen.DASHBOARD);
        Movimiento nuevo = gastoDelBot(luz, "87500");
        assertThat(pagos.estimadoDelPeriodo(luz.id(), OCTUBRE)).contains(estimado);

        PagoFijoService.Actualizacion a = pagos.actualizarEstimadoCon(nuevo.getId());

        assertThat(a.anterior()).isEqualByComparingTo("85000");
        assertThat(a.movimiento().getId()).isEqualTo(estimado.getId());
        assertThat(a.movimiento().getMonto()).isEqualByComparingTo("87500");
        assertThat(a.movimiento().isMontoEstimado()).isFalse();
        assertThat(nuevo.getEstado()).isEqualTo(EstadoMovimiento.CANCELADO);
        assertThat(nuevo.getGastoFijoId()).isNull();
        assertThat(delPeriodo(luz, OCTUBRE)).containsExactly(estimado);
        assertThat(item(luz, OCTUBRE).estado()).isEqualTo(EstadoChecklist.CARGADO);
    }

    @Test
    void sobreUnoConElMontoRealNoHayEstimadoQueActualizar() {
        VistaFijo luz = fijo("Luz", "85000", false, 28);
        fijos.cargarManual(luz.id(), new BigDecimal("86000"), HOY, Origen.TEXTO, null);
        Movimiento nuevo = gastoDelBot(luz, "87500");

        assertThat(pagos.estimadoDelPeriodo(luz.id(), OCTUBRE)).isEmpty();
        assertThat(fijos.yaCargadoEstePeriodo(luz.id(), OCTUBRE)).isPresent();
        assertThatThrownBy(() -> pagos.actualizarEstimadoCon(nuevo.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(nuevo.isPendiente()).isTrue();
    }

    @Test
    void unMesAnteriorSeTildaConLaFechaDeSuCobro() {
        VistaFijo internet = fijo("Internet", "25000", false, 28);
        reloj.ir(LocalDateTime.of(2026, 11, 2, 10, 0));

        Movimiento m = pagos.pagado(internet.id(), OCTUBRE, Origen.DASHBOARD);

        assertThat(m.getFechaHora().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(m.getFijoPeriodo()).isEqualTo(OCTUBRE.atDay(1));
    }
}
