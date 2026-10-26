package ar.gastito.juntada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;

/** Hoy es el sábado 24/10/2026 a las 23:00, y el asado fue a las 21:30. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JuntadaService.class, MovimientoService.class, JuntadaServiceTest.Config.class})
class JuntadaServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 24, 23, 0);
    static final LocalDateTime ASADO = LocalDateTime.of(2026, 10, 24, 21, 30);

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    JuntadaService juntadas;

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

    private Cuenta mercadoPago() {
        return cuentas.findAll().stream().filter(c -> c.getNombre().equals("Mercado Pago")).findFirst().orElseThrow();
    }

    private MedioPago qr() {
        return medios.findAll().stream().filter(m -> m.getNombre().equals("QR")).findFirst().orElseThrow();
    }

    /** "pagué 48000 el asado, éramos 6, con mercado pago", con los nombres que se hayan dicho. */
    private VistaJuntada asado(String total, int personas, String... nombres) {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(total), "Asado",
                categorias.findByNombre("Salidas").orElseThrow(), qr(), mercadoPago(), null, ASADO), Origen.TEXTO,
                "pagué el asado");
        return juntadas.crear(m.getId(), personas, List.of(nombres));
    }

    private VistaJuntada guardada(VistaJuntada v) {
        movimientos.confirmar(v.movimientoId());
        juntadas.alGuardar(v.movimientoId());
        return juntadas.vista(v.movimientoId()).orElseThrow();
    }

    @Test
    void enMisGastosCuentaSoloMiParteYElRestoEsACobrar() {
        VistaJuntada v = asado("48000", 6, "Martín", "Sofi", "Pablo");

        assertThat(v.totalPagado()).isEqualByComparingTo("48000");
        assertThat(v.miParte()).isEqualByComparingTo("8000");
        assertThat(v.aCobrar()).isEqualByComparingTo("40000");
        assertThat(v.participantes()).extracting(VistaJuntada.Persona::nombre)
                .containsExactly("Martín", "Sofi", "Pablo", "Persona 5", "Persona 6");
        Movimiento m = repositorio.findById(v.movimientoId()).orElseThrow();
        assertThat(m.getMonto()).isEqualByComparingTo("8000");
        assertThat(m.isJuntada()).isTrue();
        assertThat(v.billetera()).isEqualTo("Mercado Pago");
    }

    @Test
    void alGuardarQuedaAnotadoDesdeDondeSalioElTotal() {
        VistaJuntada v = guardada(asado("48000", 6));

        Juntada j = juntadaDe(v);
        assertThat(j.getCuenta().getNombre()).isEqualTo("Mercado Pago");
        assertThat(j.getMedioPago().getNombre()).isEqualTo("QR");
        assertThat(repositorio.totalGastos(ASADO.toLocalDate().atStartOfDay(), AHORA)).isEqualByComparingTo("8000");
    }

    @Test
    void unaParteDistintaYElTotalSigueCerrando() {
        VistaJuntada v = asado("48000", 6, "Martín");

        VistaJuntada cambiada = juntadas.cambiarParte(v.participantes().get(0).id(), new BigDecimal("5000"));

        assertThat(cambiada.participantes()).extracting(p -> p.monto().toPlainString())
                .containsExactly("5000.00", "8600.00", "8600.00", "8600.00", "8600.00");
        assertThat(cambiada.miParte()).isEqualByComparingTo("8600");
        assertThat(cambiada.participantes().get(0).montoAMano()).isTrue();
        // Un total nuevo respeta la parte de Martín.
        VistaJuntada otroTotal = juntadas.cambiarTotal(v.movimientoId(), new BigDecimal("53000"));
        assertThat(otroTotal.participantes()).extracting(p -> p.monto().toPlainString())
                .containsExactly("5000.00", "9600.00", "9600.00", "9600.00", "9600.00");
        assertThat(otroTotal.miParte()).isEqualByComparingTo("9600");
    }

    @Test
    void unaParteQueNoDejaNadaParaMiSeRechaza() {
        VistaJuntada v = asado("10000", 2, "Martín");

        assertThatThrownBy(() -> juntadas.cambiarParte(v.participantes().get(0).id(), new BigDecimal("10000")))
                .isInstanceOf(JuntadaInvalidaException.class);
    }

    @Test
    void unaDevolucionMarcaPagadoYNoCuentaComoIngreso() {
        VistaJuntada v = guardada(asado("48000", 6, "Martín", "Sofi", "Pablo"));
        Movimiento devolucion = juntadas.devolucionPendiente(new BigDecimal("8000"), null, null, AHORA, Origen.TEXTO,
                "pablo me pasó 8000");

        JuntadaService.Cobro cobro = juntadas.cobrar(devolucion.getId(), v.participantes().get(2).id());

        Movimiento guardada = repositorio.findById(devolucion.getId()).orElseThrow();
        assertThat(guardada.getTipo()).isEqualTo(TipoMovimiento.DEVOLUCION);
        assertThat(guardada.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(guardada.getDescripcion()).isEqualTo("Pablo, de Asado");
        assertThat(guardada.getCuenta().getNombre()).isEqualTo("Mercado Pago"); // la de la juntada
        assertThat(cobro.juntada().participantes().get(2).estado()).isEqualTo(EstadoParticipante.PAGADO);
        assertThat(cobro.juntada().aCobrar()).isEqualByComparingTo("32000");
        assertThat(repositorio.totalIngresos(ASADO.toLocalDate().atStartOfDay(), AHORA.plusHours(1))).isZero();
        assertThat(repositorio.totalGastos(ASADO.toLocalDate().atStartOfDay(), AHORA.plusHours(1)))
                .isEqualByComparingTo("8000");
    }

    @Test
    void unaDeudaPerdidaPasaASerGastoMioConLaCategoriaYLaFechaOriginales() {
        VistaJuntada v = guardada(asado("48000", 6, "Martín"));

        VistaJuntada despues = juntadas.darPorPerdido(v.participantes().get(0).id());

        assertThat(despues.participantes().get(0).estado()).isEqualTo(EstadoParticipante.PERDIDO);
        assertThat(despues.aCobrar()).isEqualByComparingTo("32000");
        Long gastoId = juntadaDe(v).getParticipantes().get(0).getGastoPerdidoId();
        Movimiento gasto = repositorio.findById(gastoId).orElseThrow();
        assertThat(gasto.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(gasto.getMonto()).isEqualByComparingTo("8000");
        assertThat(gasto.getCategoria().getNombre()).isEqualTo("Salidas");
        assertThat(gasto.getFechaHora()).isEqualTo(ASADO);
        assertThat(gasto.getDescripcion()).isEqualTo("Asado: la parte de Martín (no la cobré)");
        assertThat(repositorio.totalGastos(ASADO.toLocalDate().atStartOfDay(), AHORA)).isEqualByComparingTo("16000");
        // Ya no se puede volver a perder ni cobrar.
        assertThatThrownBy(() -> juntadas.darPorPerdido(v.participantes().get(0).id()))
                .isInstanceOf(JuntadaInvalidaException.class);
    }

    @Test
    void unaJuntadaSinGuardarNoSeDaPorPerdida() {
        VistaJuntada v = asado("48000", 6);

        assertThatThrownBy(() -> juntadas.darPorPerdido(v.participantes().get(0).id()))
                .hasMessageContaining("guardá");
    }

    @Test
    void aQuienCorrespondeUnaDevolucion() {
        VistaJuntada asado = guardada(asado("48000", 6, "Martín", "Pablo"));
        Movimiento pizza = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("30000"),
                "Pizza", categorias.findByNombre("Comida afuera / delivery").orElseThrow(), null, null, null,
                ASADO.minusDays(3)), Origen.TEXTO, "pizzas");
        VistaJuntada pizzas = guardada(juntadas.crear(pizza.getId(), 3, List.of("Pablo")));

        // Por nombre: Pablo está en las dos.
        assertThat(juntadas.deudores("Pablo", null, null)).extracting(d -> d.juntada().movimientoId())
                .containsExactlyInAnyOrder(asado.movimientoId(), pizzas.movimientoId());
        // Con el monto, una sola (la pizza: $10.000 cada uno).
        assertThat(juntadas.deudores("pablo", null, new BigDecimal("10000"))).singleElement()
                .satisfies(d -> assertThat(d.juntada().movimientoId()).isEqualTo(pizzas.movimientoId()));
        // Por la juntada: "del asado".
        assertThat(juntadas.deudores(null, "el asado", null)).extracting(JuntadaService.Deudor::nombre)
                .containsExactly("Martín", "Pablo", "Persona 4", "Persona 5", "Persona 6");
        // Un nombre que no está no descarta a nadie.
        assertThat(juntadas.deudores("Juan", null, null)).hasSize(7);
    }

    private Juntada juntadaDe(VistaJuntada v) {
        return juntadasRepo.findByMovimientoId(v.movimientoId()).orElseThrow();
    }

    @Autowired
    JuntadaRepository juntadasRepo;
}
