package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({IngresoService.class, MovimientoService.class, IngresoServiceTest.Reloj.class})
class IngresoServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    /** 21/10/2026 16:42 en Argentina. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 21, 16, 42);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    IngresoService ingresos;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    CuentaRepository cuentas;

    private Cuenta cuenta(String nombre) {
        return cuentas.findAll().stream().filter(c -> c.getNombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Movimiento cargar(ClaseIngreso clase, String monto, String cuenta, LocalDate fecha) {
        return ingresos.cargar(clase, new BigDecimal(monto), null, cuenta == null ? null : cuenta(cuenta).getId(),
                fecha);
    }

    @Test
    void desdeElDashboardQuedaGuardadoEnLaCuenta() {
        Movimiento m = ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), "  sueldo de septiembre ",
                cuenta("Galicia").getId(), LocalDate.of(2026, 10, 1));

        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.INGRESO);
        assertThat(m.getOrigen()).isEqualTo(Origen.DASHBOARD);
        assertThat(m.getDescripcion()).isEqualTo("Sueldo de septiembre");
        assertThat(m.getCuenta().getNombre()).isEqualTo("Galicia");
        assertThat(m.getMedioPago()).isNull();
        assertThat(m.getFechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void sinCuentaEsEnEfectivo() {
        Movimiento m = cargar(ClaseIngreso.EXTRA, "25000", null, LocalDate.of(2026, 10, 12));

        assertThat(m.getCuenta()).isNull();
        assertThat(m.getMedioPago().isEfectivo()).isTrue();
    }


    @Test
    void elSueldoCobradoHastaElQuinceEsDelMesAnterior() {
        assertThat(cargar(ClaseIngreso.SUELDO, "100", null, LocalDate.of(2026, 10, 5)).getPeriodo())
                .isEqualTo(YearMonth.of(2026, 9));
        assertThat(cargar(ClaseIngreso.SUELDO, "100", null, LocalDate.of(2026, 10, 15)).getPeriodo())
                .isEqualTo(YearMonth.of(2026, 9));
        // El 5 de enero el período es diciembre del año anterior.
        assertThat(ingresos.periodoPorDefecto(ClaseIngreso.SUELDO, LocalDate.of(2027, 1, 5)))
                .isEqualTo(YearMonth.of(2026, 12));
    }

    @Test
    void elSueldoCobradoDespuesDelQuinceEsDelMismoMes() {
        assertThat(cargar(ClaseIngreso.SUELDO, "100", null, LocalDate.of(2026, 10, 16)).getPeriodo())
                .isEqualTo(YearMonth.of(2026, 10));
        assertThat(cargar(ClaseIngreso.SUELDO, "100", null, LocalDate.of(2026, 10, 21)).getPeriodo())
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void elAguinaldoEsDelMesDelCobroYUnExtraNoTienePeriodo() {
        assertThat(cargar(ClaseIngreso.AGUINALDO, "100", null, LocalDate.of(2026, 10, 5)).getPeriodo())
                .isEqualTo(YearMonth.of(2026, 10));
        assertThat(cargar(ClaseIngreso.EXTRA, "100", null, LocalDate.of(2026, 10, 5)).getPeriodo()).isNull();
    }

    @Test
    void laFechaDeCobroSigueContandoParaElDisponibleDeEseMes() {
        cargar(ClaseIngreso.SUELDO, "1000", null, LocalDate.of(2026, 10, 5));

        // Es el sueldo de septiembre, pero entró en octubre.
        assertThat(ingresos.totalDelMes(OCTUBRE)).isEqualByComparingTo("1000");
        assertThat(ingresos.totalDelMes(YearMonth.of(2026, 9))).isEqualByComparingTo("0");
        assertThat(ingresos.sueldoDelPeriodo(YearMonth.of(2026, 9))).isEqualByComparingTo("1000");
        assertThat(ingresos.sueldoDelPeriodo(OCTUBRE)).isEqualByComparingTo("0");
    }

    @Test
    void elPeriodoDeUnSueldoPendienteSeCambiaYVuelve() {
        Movimiento pendiente = ingresos.crearPendiente(ClaseIngreso.SUELDO, new BigDecimal("1000"), null, null,
                true, LocalDateTime.of(2026, 10, 5, 12, 0), "/sueldo 1000");
        assertThat(pendiente.getPeriodo()).isEqualTo(YearMonth.of(2026, 9));

        assertThat(ingresos.alternarPeriodo(pendiente.getId()).getPeriodo()).isEqualTo(OCTUBRE);
        assertThat(ingresos.alternarPeriodo(pendiente.getId()).getPeriodo()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void unIngresoSinPeriodoCuentaEnElMesDeSuFecha() {
        Movimiento viejo = cargar(ClaseIngreso.SUELDO, "700", null, LocalDate.of(2026, 10, 1));
        viejo.definirPeriodo(null);

        assertThat(ingresos.sueldoDelPeriodo(OCTUBRE)).isEqualByComparingTo("700");
    }
    @Test
    void rechazaMontoFechaFuturaYCuentaInexistente() {
        assertThatThrownBy(() -> cargar(ClaseIngreso.SUELDO, "0", "Galicia", LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mayor a cero");
        assertThatThrownBy(() -> cargar(ClaseIngreso.SUELDO, "100", "Galicia", LocalDate.of(2026, 10, 22)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("futura");
        assertThatThrownBy(() -> ingresos.cargar(ClaseIngreso.SUELDO, BigDecimal.TEN, null, 9999L,
                LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cuenta");
        assertThatThrownBy(() -> ingresos.cargar(null, BigDecimal.TEN, null, null, LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elResumenDelMesSeparaSueldoAguinaldoYExtras() {
        cargar(ClaseIngreso.SUELDO, "380000", "Galicia", LocalDate.of(2026, 10, 1));
        cargar(ClaseIngreso.AGUINALDO, "190000", "Galicia", LocalDate.of(2026, 10, 3));
        cargar(ClaseIngreso.EXTRA, "25000", "Mercado Pago", LocalDate.of(2026, 10, 12));
        cargar(ClaseIngreso.EXTRA, "5000", null, LocalDate.of(2026, 10, 15));
        cargar(ClaseIngreso.SUELDO, "356000", "Galicia", LocalDate.of(2026, 9, 30)); // otro mes
        ingresos.anular(cargar(ClaseIngreso.EXTRA, "99999", null, LocalDate.of(2026, 10, 2)).getId()); // borrado

        IngresoService.ResumenIngresos r = ingresos.delMes(OCTUBRE);

        assertThat(r.ingresos()).hasSize(4);
        assertThat(r.total()).isEqualByComparingTo("600000");
        assertThat(r.sueldo()).isEqualByComparingTo("380000");
        assertThat(r.aguinaldo()).isEqualByComparingTo("190000");
        assertThat(r.extras()).isEqualByComparingTo("30000");
        assertThat(r.cantidadExtras()).isEqualTo(2);
        assertThat(ingresos.totalDelMes(OCTUBRE)).isEqualByComparingTo("600000");
    }

    @Test
    void unMesSinIngresosDaCero() {
        IngresoService.ResumenIngresos r = ingresos.delMes(OCTUBRE);

        assertThat(r.ingresos()).isEmpty();
        assertThat(r.total()).isEqualByComparingTo("0");
        assertThat(r.cantidadExtras()).isZero();
    }

    @Test
    void porMesTraeLosUltimosMesesConCeros() {
        cargar(ClaseIngreso.SUELDO, "356000", "Galicia", LocalDate.of(2026, 8, 1));
        cargar(ClaseIngreso.SUELDO, "380000", "Galicia", LocalDate.of(2026, 10, 1));
        cargar(ClaseIngreso.EXTRA, "25000", null, LocalDate.of(2026, 10, 12));
        cargar(ClaseIngreso.SUELDO, "1", "Galicia", LocalDate.of(2026, 4, 30)); // fuera de los 6 meses

        assertThat(ingresos.porMes(OCTUBRE, 6))
                .extracting(TotalPorMes::yearMonth, t -> t.total().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple(YearMonth.of(2026, 5), "0"),
                        tuple(YearMonth.of(2026, 6), "0"),
                        tuple(YearMonth.of(2026, 7), "0"),
                        tuple(YearMonth.of(2026, 8), "356000"),
                        tuple(YearMonth.of(2026, 9), "0"),
                        tuple(YearMonth.of(2026, 10), "405000"));
    }

    @Test
    void anularSoloBorraIngresosGuardados() {
        Movimiento sueldo = cargar(ClaseIngreso.SUELDO, "380000", "Galicia", LocalDate.of(2026, 10, 1));

        ingresos.anular(sueldo.getId());

        assertThat(movimientos.buscar(sueldo.getId()).getEstado()).isEqualTo(EstadoMovimiento.CANCELADO);
        assertThatThrownBy(() -> ingresos.anular(sueldo.getId())).isInstanceOf(IllegalStateException.class);
    }

    // --- Desde el bot ---

    @Test
    void desdeElBotQuedaPendienteYSeGuardaConElTotalDeIngresosDelMes() {
        cargar(ClaseIngreso.EXTRA, "25000", null, LocalDate.of(2026, 10, 12));
        Movimiento m = ingresos.crearPendiente(ClaseIngreso.SUELDO, new BigDecimal("380000"), null,
                cuenta("Galicia"), false, AHORA, "/sueldo 380000 galicia");

        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.PENDIENTE);
        assertThat(m.getOrigen()).isEqualTo(Origen.TEXTO);

        MovimientoService.Confirmacion c = movimientos.confirmar(m.getId());
        assertThat(c.movimiento().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(c.totalMesCategoria()).isEqualByComparingTo("405000");
    }

    @Test
    void desdeElBotSinCuentaUsaLaDelUltimoIngresoDeEsaClase() {
        cargar(ClaseIngreso.SUELDO, "356000", "Galicia", LocalDate.of(2026, 9, 1));
        cargar(ClaseIngreso.EXTRA, "5000", null, LocalDate.of(2026, 9, 20));

        Movimiento sueldo = ingresos.crearPendiente(ClaseIngreso.SUELDO, new BigDecimal("380000"), null, null,
                false, AHORA, "/sueldo 380000");
        Movimiento extra = ingresos.crearPendiente(ClaseIngreso.EXTRA, new BigDecimal("1000"), null, null,
                false, AHORA, "/ingreso 1000");
        Movimiento aguinaldo = ingresos.crearPendiente(ClaseIngreso.AGUINALDO, new BigDecimal("190000"), null, null,
                false, AHORA, "/aguinaldo 190000");

        assertThat(sueldo.getCuenta().getNombre()).isEqualTo("Galicia");
        assertThat(extra.getCuenta()).isNull();
        assertThat(extra.getMedioPago().isEfectivo()).isTrue();
        // Nunca hubo un aguinaldo: queda sin especificar.
        assertThat(aguinaldo.getCuenta()).isNull();
        assertThat(aguinaldo.getMedioPago()).isNull();
    }
}
