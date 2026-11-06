package ar.gastito.prestamo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoEspecialException;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;

/** Hoy es el viernes 6/11/2026. Los vuelos se pagaron hoy por transferencia desde Galicia. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PrestamoService.class, MovimientoService.class, PrestamoServiceTest.Config.class})
class PrestamoServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 6, 12, 0);

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }

        @Bean
        PrestamosProperties prestamosProperties() {
            return new PrestamosProperties(3, 10);
        }
    }

    @Autowired
    PrestamoService prestamos;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    MovimientoRepository repositorio;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @Autowired
    CuentaRepository cuentas;

    private Cuenta galicia() {
        return cuentas.findAll().stream().filter(c -> c.getNombre().equals("Galicia")).findFirst().orElseThrow();
    }

    private MedioPago transferencia() {
        return medios.findAll().stream().filter(m -> m.getNombre().equals("Transferencia")).findFirst().orElseThrow();
    }

    private Movimiento vuelos(String total) {
        return movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(total), "Vuelos",
                categorias.findByNombre("Otros").orElseThrow(), transferencia(), galicia(), null, AHORA),
                Origen.TEXTO, "pagué los vuelos");
    }

    /** "Pagué 293.000 de vuelos; 30.000 son de Martín, que me los devuelve en 6 cuotas de 5.000 desde diciembre". */
    private VistaPrestamo martin() {
        Movimiento m = vuelos("293000");
        return prestamos.crearConGasto(m.getId(), new PrestamoService.Datos("Martín", new BigDecimal("30000"), 6,
                new BigDecimal("5000"), YearMonth.of(2026, 12), null));
    }

    private VistaPrestamo guardado() {
        VistaPrestamo v = martin();
        movimientos.confirmar(v.movimientoId());
        return v;
    }

    private Movimiento devolucion(String monto) {
        return repositorio.save(Movimiento.devolucion(new BigDecimal(monto), "Devolución", null, null, AHORA,
                Origen.TEXTO, "martín me pasó", AHORA));
    }

    @Test
    void cargadoConElGastoMiParteEsLaDiferenciaYElPrestamoSaleDelMismoPago() {
        VistaPrestamo v = martin();

        Movimiento gasto = movimientos.buscar(v.movimientoId());
        assertThat(gasto.getMonto()).isEqualByComparingTo("263000");
        assertThat(v.monto()).isEqualByComparingTo("30000");
        assertThat(v.cantidadCuotas()).isEqualTo(6);
        assertThat(v.billetera()).isEqualTo("Galicia");
        assertThat(v.cuotas()).extracting(VistaPrestamo.Cuota::vence).startsWith(LocalDate.of(2026, 12, 6),
                LocalDate.of(2027, 1, 6));
        // Mientras el gasto no está guardado, el préstamo no cuenta.
        assertThat(prestamos.conDeuda()).isEmpty();
        movimientos.confirmar(v.movimientoId());
        assertThat(prestamos.conDeuda()).hasSize(1);
    }

    @Test
    void elPrestamoNoPuedeSerTodoElPago() {
        Movimiento m = vuelos("30000");
        assertThatThrownBy(() -> prestamos.crearConGasto(m.getId(), new PrestamoService.Datos("Martín",
                new BigDecimal("30000"), 6, null, null, null))).isInstanceOf(PrestamoInvalidoException.class);
    }

    @Test
    void cambiarElTotalDelGastoDejaElPrestamoComoEstaba() {
        VistaPrestamo v = martin();
        assertThat(prestamos.cambiarTotal(v.movimientoId(), new BigDecimal("300000")).getMonto())
                .isEqualByComparingTo("270000");
    }

    @Test
    void cobrarLaCuotaLaMarcaYDiceCuantoFalta() {
        VistaPrestamo v = guardado();

        PrestamoService.Cobro c = prestamos.cobrar(devolucion("5000").getId(), v.id());

        assertThat(c.devolucion().getTipo()).isEqualTo(TipoMovimiento.DEVOLUCION);
        assertThat(c.devolucion().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(c.devolucion().getCuenta().getNombre()).isEqualTo("Galicia");
        assertThat(c.devolucion().getDescripcion()).isEqualTo("Martín, cuota 1 de 6 (Vuelos)");
        assertThat(c.prestamo().cuotasCobradas()).isEqualTo(1);
        assertThat(c.prestamo().restante()).isEqualByComparingTo("25000");
        assertThat(c.acomodado()).isFalse();
    }

    @Test
    void pagarDeMenosAcomodaLasSiguientes() {
        VistaPrestamo v = guardado();

        PrestamoService.Cobro c = prestamos.cobrar(devolucion("3000").getId(), v.id());

        assertThat(c.acomodado()).isTrue();
        assertThat(c.prestamo().restante()).isEqualByComparingTo("27000");
        assertThat(c.prestamo().cuotas()).extracting(VistaPrestamo.Cuota::monto).map(BigDecimal::intValue)
                .containsExactly(3000, 5400, 5400, 5400, 5400, 5400);
    }

    @Test
    void pagarDeMasCubreVariasYAcomodaElResto() {
        VistaPrestamo v = guardado();

        PrestamoService.Cobro c = prestamos.cobrar(devolucion("12000").getId(), v.id());

        assertThat(c.cuotas()).isEqualTo(2);
        assertThat(c.devolucion().getDescripcion()).startsWith("Martín, cuotas 1 a 2 de 6");
        assertThat(c.prestamo().cuotas()).extracting(VistaPrestamo.Cuota::monto).map(BigDecimal::intValue)
                .containsExactly(5000, 7000, 4500, 4500, 4500, 4500);
    }

    @Test
    void pagarTodoLoTermina() {
        VistaPrestamo v = guardado();

        PrestamoService.Cobro c = prestamos.cobrar(devolucion("31000").getId(), v.id());

        assertThat(c.prestamo().terminado()).isTrue();
        assertThat(c.excedente()).isEqualByComparingTo("1000");
        assertThat(prestamos.conDeuda()).isEmpty();
    }

    @Test
    void unaCuotaVencidaHaceUnosDiasQuedaAtrasadaYSeAvisaUnaVez() {
        // Un préstamo de agosto con la primera cuota el 1/10: pasó hace más de 3 días.
        VistaPrestamo v = prestamos.crearSolo(new PrestamoService.Datos("Sofi", new BigDecimal("20000"), 2, null,
                YearMonth.of(2026, 10), 1), null, galicia(), transferencia(), LocalDate.of(2026, 8, 20), null, null);
        prestamos.confirmar(v.id());

        var avisar = prestamos.paraAvisar();

        assertThat(avisar).hasSize(1);
        assertThat(avisar.get(0).cuota().numero()).isEqualTo(1);
        assertThat(prestamos.vista(v.id()).cuotas().get(0).estado()).isEqualTo(EstadoCuota.ATRASADA);
        // La del 1/11 vence hace 5 días: también atrasada, pero se avisa la más vieja, una vez.
        prestamos.avisada(v.id(), avisar.get(0).cuota().id());
        assertThat(prestamos.paraAvisar()).extracting(a -> a.cuota().numero()).containsExactly(2);
    }

    @Test
    void unaCuotaQueVenceHoyTodaviaNoEstaAtrasada() {
        VistaPrestamo v = prestamos.crearSolo(new PrestamoService.Datos("Sofi", new BigDecimal("20000"), 2, null,
                YearMonth.of(2026, 11), 4), null, galicia(), transferencia(), AHORA.toLocalDate(), null, null);
        prestamos.confirmar(v.id());

        assertThat(prestamos.paraAvisar()).isEmpty();
    }

    @Test
    void unPrestamoCargadoSoloNoCuentaHastaConfirmarlo() {
        VistaPrestamo v = prestamos.crearSolo(new PrestamoService.Datos("Sofi", new BigDecimal("20000"), null, null,
                null, null), "Entrada", galicia(), transferencia(), AHORA.toLocalDate(), null, null);

        assertThat(v.estado()).isEqualTo(EstadoPrestamo.PENDIENTE);
        assertThat(v.primerMes()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(prestamos.conDeuda()).isEmpty();
        prestamos.confirmar(v.id());
        assertThat(prestamos.conDeuda()).hasSize(1);
    }

    @Test
    void lasDevolucionesSeBuscanPorNombre() {
        guardado();
        assertThat(prestamos.deudores("martin", null)).hasSize(1);
        assertThat(prestamos.deudores("Pablo", null)).isEmpty();
    }

    @Test
    void borrarElGastoBorraElPrestamoSiNoHayCobrosYSiLosHayNoSeBorra() {
        VistaPrestamo v = guardado();
        prestamos.cobrar(devolucion("5000").getId(), v.id());
        assertThatThrownBy(() -> movimientos.borrar(v.movimientoId())).isInstanceOf(MovimientoEspecialException.class);

        VistaPrestamo otro = guardado();
        movimientos.borrar(otro.movimientoId());
        assertThat(prestamos.todos()).extracting(VistaPrestamo::id).doesNotContain(otro.id());
    }
}
