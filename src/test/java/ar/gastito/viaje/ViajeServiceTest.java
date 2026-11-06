package ar.gastito.viaje;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.prestamo.PrestamoService;
import ar.gastito.prestamo.PrestamosProperties;

/** Hoy es el viernes 6/11/2026; Bariloche es del 10 al 17 de enero. El sueldo se cobra los 5 (ya entró el de hoy). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ViajeService.class, ModoViaje.class, MovimientoService.class, PrestamoService.class, JuntadaService.class,
        ViajeServiceTest.Config.class})
class ViajeServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 6, 12, 0);
    static final LocalDate DESDE = LocalDate.of(2027, 1, 10);
    static final LocalDate HASTA = LocalDate.of(2027, 1, 17);

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

        @Bean
        ViajesProperties viajesProperties() {
            return new ViajesProperties(5, 9);
        }
    }

    @Autowired
    ViajeService viajes;

    @Autowired
    ModoViaje modo;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    JuntadaService juntadas;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    TestEntityManager em;

    private PanelViaje bariloche() {
        return viajes.crear(new ViajeService.Datos("Bariloche", null, DESDE, HASTA, 2, null, null));
    }

    private Movimiento gasto(String monto, LocalDateTime cuando, Origen origen) {
        return movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Algo",
                categorias.findByNombre("Salidas").orElseThrow(), null, null, null, cuando), origen, "algo");
    }

    private void sueldoCobradoEl5() {
        Movimiento sueldo = gasto("900000", LocalDateTime.of(2026, 11, 5, 10, 0), Origen.DASHBOARD);
        movimientos.confirmar(sueldo.getId());
        em.flush();
        em.getEntityManager().createNativeQuery(
                "update movimiento set tipo = 'INGRESO', clase_ingreso = 'SUELDO', categoria_id = null where id = "
                        + sueldo.getId()).executeUpdate();
        em.clear();
    }

    @Test
    void elEmojiSaleDelNombre() {
        assertThat(bariloche().nombreConEmoji()).isEqualTo("🏔 Bariloche");
    }

    @Test
    void loQueFaltaSumaSoloLosPendientesConMonto() {
        Long id = bariloche().id();
        viajes.agregarPendiente(id, "hospedaje", new BigDecimal("400000"), null);
        PanelViaje p = viajes.agregarPendiente(id, "excursiones", null, null);

        assertThat(p.falta()).isEqualByComparingTo("400000");
        assertThat(p.sinMonto()).isEqualTo(1);
        assertThat(p.pendientes()).extracting(PanelViaje.Pendiente::descripcion)
                .containsExactly("Hospedaje", "Excursiones");
        assertThat(p.totalEstimado()).isEqualByComparingTo("400000");
    }

    @Test
    void pagarUnPendienteCreaElGastoDelViajeYDejaDeFaltar() {
        Long id = bariloche().id();
        PanelViaje p = viajes.agregarPendiente(id, "Hospedaje", new BigDecimal("400000"), null);
        Long pendiente = p.pendientes().get(0).id();

        PanelViaje pagado = viajes.pagarPendiente(pendiente, new BigDecimal("380000"),
                categorias.findByNombre("Salidas").orElseThrow().getId(), null, null, null);

        assertThat(pagado.pagado()).isEqualByComparingTo("380000");
        assertThat(pagado.falta()).isZero();
        assertThat(pagado.pendientes().get(0).pagado()).isTrue();
        Movimiento gasto = movimientos.buscar(pagado.pendientes().get(0).movimientoId());
        assertThat(gasto.getViajeId()).isEqualTo(id);
        assertThat(gasto.getDescripcion()).isEqualTo("Hospedaje (Bariloche)");
    }

    @Test
    void unPendienteDelBotQuedaPagadoRecienCuandoSeGuardaElGasto() {
        Long id = bariloche().id();
        Long pendiente = viajes.agregarPendiente(id, "Hospedaje", new BigDecimal("400000"), null).pendientes()
                .get(0).id();

        Movimiento m = viajes.pendienteComoGasto(pendiente);
        assertThat(viajes.panel(id).falta()).isEqualByComparingTo("400000");
        movimientos.elegirCategoria(m.getId(), categorias.findByNombre("Salidas").orElseThrow().getId());
        movimientos.confirmar(m.getId());

        assertThat(viajes.panel(id).falta()).isZero();
        // Si se borra el gasto, vuelve a faltar.
        movimientos.borrar(m.getId());
        assertThat(viajes.panel(id).falta()).isEqualByComparingTo("400000");
    }

    @Test
    void enElModoViajeLoQueSeCargaSeAsociaSolo() {
        Long id = bariloche().id();

        Movimiento durante = gasto("12000", LocalDateTime.of(2027, 1, 12, 13, 0), Origen.TEXTO);
        Movimiento ultimoDia = gasto("5000", LocalDateTime.of(2027, 1, 17, 22, 0), Origen.AUDIO);
        Movimiento despues = gasto("3000", LocalDateTime.of(2027, 1, 18, 9, 0), Origen.TEXTO);
        Movimiento cobroDeFijo = gasto("9000", LocalDateTime.of(2027, 1, 12, 13, 0), Origen.AUTOMATICO);

        assertThat(durante.getViajeId()).isEqualTo(id);
        assertThat(ultimoDia.getViajeId()).isEqualTo(id);
        assertThat(despues.getViajeId()).isNull();
        assertThat(cobroDeFijo.getViajeId()).isNull();
        assertThat(modo.viajeDe(DESDE)).contains(id);
    }

    @Test
    void noEsDelViajeLoSaca() {
        bariloche();
        Movimiento m = gasto("12000", LocalDateTime.of(2027, 1, 12, 13, 0), Origen.TEXTO);

        assertThat(viajes.quitarDelViaje(m.getId()).getViajeId()).isNull();
    }

    @Test
    void lasJuntadasDelViajeQuedanAsociadasYCuentaLoQuePusePorLosDemas() {
        Long id = bariloche().id();
        Movimiento cena = gasto("40000", LocalDateTime.of(2027, 1, 12, 22, 0), Origen.TEXTO);
        juntadas.crear(cena.getId(), 4, List.of("Martín"));
        movimientos.confirmar(cena.getId());

        PanelViaje p = viajes.panel(id);

        assertThat(p.juntadas()).hasSize(1);
        assertThat(p.pagado()).isEqualByComparingTo("10000");
        assertThat(p.porLosDemas()).isEqualByComparingTo("30000");
        assertThat(p.meDeben()).isEqualByComparingTo("30000");
        assertThat(p.porDia()).filteredOn(d -> d.dia().equals(LocalDate.of(2027, 1, 12)))
                .extracting(PanelViaje.PorDia::total).first().satisfies(t -> assertThat(t).isEqualByComparingTo("10000"));
    }

    @Test
    void cuantoAhorrarSinReservas() {
        sueldoCobradoEl5();
        Long id = bariloche().id();

        AhorroViaje a = viajes.agregarPendiente(id, "Hospedaje", new BigDecimal("400000"), null).ahorro();

        assertThat(a.cobros()).isEqualTo(2);
        assertThat(a.reservado()).isZero();
        assertThat(a.porMes()).isEqualByComparingTo("200000");
    }

    @Test
    void cuantoAhorrarConLoReservadoEnInversiones() {
        sueldoCobradoEl5();
        Long id = bariloche().id();
        em.getEntityManager().createNativeQuery("insert into cuenta (nombre, activa, tipo) values ('Cocos', true, "
                + "'INVERSION')").executeUpdate();
        em.getEntityManager().createNativeQuery("insert into reserva_inversion (cuenta_id, meta, monto, creada_en) "
                + "select id, 'Viaje a Bariloche', 100000, current_timestamp from cuenta where nombre = 'Cocos'")
                .executeUpdate();

        AhorroViaje a = viajes.agregarPendiente(id, "Hospedaje", new BigDecimal("400000"), null).ahorro();

        assertThat(a.reservado()).isEqualByComparingTo("100000");
        assertThat(a.porMes()).isEqualByComparingTo("150000");
    }

    @Test
    void elPresupuestoEsOpcional() {
        PanelViaje sin = bariloche();
        assertThat(sin.porcentajePresupuesto()).isNull();
        PanelViaje con = viajes.editar(sin.id(), new ViajeService.Datos("Bariloche", null, DESDE, HASTA, 2,
                new BigDecimal("1000000"), null));
        viajes.agregarPendiente(con.id(), "Hospedaje", new BigDecimal("400000"), null);
        assertThat(viajes.panel(con.id()).porcentajePresupuesto()).isEqualByComparingTo("40.0");
        assertThat(YearMonth.from(con.desde())).isEqualTo(YearMonth.of(2027, 1));
    }
}
