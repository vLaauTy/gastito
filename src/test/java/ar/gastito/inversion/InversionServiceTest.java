package ar.gastito.inversion;

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

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.dashboard.DashboardService;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.InicioRegistro;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;
import ar.gastito.resumen.RelojDePrueba;

/**
 * Las inversiones con datos en H2: el saldo inicial y los aportes y retiros no son ingresos ni gastos, las
 * valuaciones y el rendimiento, los ajustes al mover plata, las reservas y el recordatorio. Hoy es el jueves
 * 5/11/2026 a las 11.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({InversionService.class, PanelInversiones.class, ValuacionesManuales.class, DashboardService.class,
        IngresoService.class, MovimientoService.class, InicioRegistro.class, InversionServiceTest.Config.class})
class InversionServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 11, 5, 11, 0);
    static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    @TestConfiguration
    static class Config {

        @Bean
        RelojDePrueba clock() {
            return new RelojDePrueba(HOY, ARGENTINA);
        }

        @Bean
        InversionesProperties inversionesProperties() {
            return new InversionesProperties(7, 10);
        }
    }

    @Autowired
    InversionService inversiones;

    @Autowired
    PanelInversiones panel;

    @Autowired
    DashboardService dashboard;

    @Autowired
    CuentaRepository cuentas;

    @Autowired
    RelojDePrueba reloj;

    Cuenta cocos;
    Long spy;
    Long fci;
    Long galicia;

    @BeforeEach
    void saldoInicial() {
        reloj.ir(HOY);
        InversionService.CargaInicial carga = inversiones.cargarSaldoInicial(null, "Cocos", new BigDecimal("850000"),
                List.of(new InversionService.SaldoPedido("CEDEAR SPY", new BigDecimal("500000")),
                        new InversionService.SaldoPedido("un FCI", new BigDecimal("350000"))),
                OCT_1, "tengo 850.000 invertidos en Cocos, 500.000 en CEDEAR SPY y 350.000 en un FCI");
        cocos = carga.cuenta();
        assertThat(carga.cuentaNueva()).isTrue();
        spy = inversiones.posicion(cocos.getId(), "cedear spy").getId();
        fci = inversiones.posicion(cocos.getId(), "FCI").getId();
        galicia = cuentas.findAll().stream().filter(c -> c.getNombre().equals("Galicia")).findFirst().orElseThrow()
                .getId();
    }

    private PanelInversiones.EstadoCuenta estado() {
        return panel.cuenta(cocos.getId());
    }

    private PanelInversiones.Tenencia tenencia(Long posicionId) {
        return estado().tenencias().stream().filter(t -> java.util.Objects.equals(t.posicionId(), posicionId))
                .findFirst().orElseThrow();
    }

    @Test
    void elSaldoInicialNoEsUnIngreso() {
        assertThat(cocos.isInversion()).isTrue();
        assertThat(cocos.getRecordatorioDias()).isEqualTo(7);
        PanelInversiones.EstadoCuenta e = estado();
        assertThat(e.aportado()).isEqualByComparingTo("850000");
        assertThat(e.valor()).isEqualByComparingTo("850000");
        assertThat(e.tenencias()).extracting(PanelInversiones.Tenencia::nombre).containsExactly("CEDEAR SPY", "FCI");

        DashboardService.Resumen octubre = dashboard.resumen(YearMonth.of(2026, 10));
        assertThat(octubre.ingresos().total()).isEqualByComparingTo("0");
        assertThat(octubre.gastado()).isEqualByComparingTo("0");
    }

    @Test
    void dosCuentasEnUnMensaje() {
        ParserInversion.Contexto ctx = new ParserInversion.Contexto(inversiones.cuentas(), inversiones.posiciones(),
                cuentas.findByActivaTrueAndTipoOrderById(ar.gastito.catalogo.TipoCuenta.BANCARIA));
        ParserInversion.SaldosIniciales v = (ParserInversion.SaldosIniciales) ParserInversion
                .parsear(ParserInversionTest.DOS_CUENTAS, ctx).orElseThrow();

        List<InversionService.CargaInicial> cargas = inversiones.cargarSaldosIniciales(v.cuentas().stream()
                .map(s -> new InversionService.SaldoDeCuenta(s.cuenta() == null ? null : s.cuenta().getId(),
                        s.nombreCuenta(), s.total(), s.posiciones())).toList(), HOY.toLocalDate(), "texto");

        assertThat(cargas).extracting(c -> c.cuenta().getNombre()).containsExactly("Reserva de Mercado Pago", "Cocos");
        assertThat(panel.cuenta(cargas.get(0).cuenta().getId()).valor()).isEqualByComparingTo("505133");
        assertThat(estado().tenencias()).filteredOn(t -> t.nombre().equals("Acciones")).singleElement()
                .satisfies(t -> assertThat(t.aportado()).isEqualByComparingTo("680000"));
    }

    @Test
    void laCuentaDeInversionNoEsUnMedioDePago() {
        assertThat(inversiones.cuentas()).extracting(Cuenta::getNombre).containsExactly("Cocos");
        assertThat(cuentas.findByActivaTrueAndTipoOrderById(ar.gastito.catalogo.TipoCuenta.BANCARIA))
                .extracting(Cuenta::getNombre).doesNotContain("Cocos");
    }

    @Test
    void otroSaldoInicialReemplazaAlAnterior() {
        InversionService.CargaInicial otra = inversiones.cargarSaldoInicial(cocos.getId(), null, null,
                List.of(new InversionService.SaldoPedido("SPY", new BigDecimal("450000"))), OCT_1, "corrijo");

        assertThat(otra.reemplazados()).hasSize(1);
        assertThat(otra.reemplazados().getFirst().monto()).isEqualByComparingTo("500000");
        assertThat(tenencia(spy).aportado()).isEqualByComparingTo("450000");
    }

    @Test
    void aporteYRetiroSonEntreCuentasYNoCuentanComoGastoNiIngreso() {
        Movimiento aporte = inversiones.aportar(cocos.getId(), null, new BigDecimal("200000"), galicia,
                HOY.minusDays(2), Origen.TEXTO, "pasé 200.000 de Galicia a Cocos");
        Movimiento retiro = inversiones.retirar(cocos.getId(), null, new BigDecimal("150000"), galicia,
                HOY.minusDays(1), Origen.TEXTO, "saqué 150.000 de Cocos a Galicia");

        assertThat(aporte.getTipo()).isEqualTo(TipoMovimiento.ENTRE_CUENTAS);
        assertThat(aporte.getCuenta().getNombre()).isEqualTo("Galicia");
        assertThat(aporte.getCuentaDestino().getNombre()).isEqualTo("Cocos");
        assertThat(retiro.getCuenta().getNombre()).isEqualTo("Cocos");
        assertThat(retiro.isDeInversion()).isTrue();
        assertThat(estado().aportado()).isEqualByComparingTo("900000");

        DashboardService.Resumen noviembre = dashboard.resumen(YearMonth.of(2026, 11));
        assertThat(noviembre.gastado()).isEqualByComparingTo("0");
        assertThat(noviembre.ingresos().total()).isEqualByComparingTo("0");
        assertThat(panel.aportesNetos(HOY.withDayOfMonth(1).toLocalDate().atStartOfDay(), HOY.plusDays(1)))
                .isEqualByComparingTo("50000");
    }

    @Test
    void elAporteEnEfectivoNoTieneCuentaDeOrigen() {
        Movimiento aporte = inversiones.aportar(cocos.getId(), null, new BigDecimal("10000"), null, HOY, Origen.TEXTO,
                "puse 10 mil en Cocos");

        assertThat(aporte.getCuenta()).isNull();
        assertThat(aporte.getMedioPago().isEfectivo()).isTrue();
    }

    @Test
    void valuacionesYRendimiento() {
        inversiones.valuar(cocos.getId(), spy, new BigDecimal("520000"), HOY.toLocalDate());

        PanelInversiones.Tenencia s = tenencia(spy);
        assertThat(s.rendimiento()).isEqualByComparingTo("20000");
        assertThat(s.porcentaje()).isEqualByComparingTo("4.00");

        // "Cocos vale 800.000" es el total: las posiciones ya valen 870.000, no alcanza.
        assertThatThrownBy(() -> inversiones.valuar(cocos.getId(), null, new BigDecimal("800000"), HOY.toLocalDate()))
                .isInstanceOf(IllegalArgumentException.class);

        // Con 200.000 sin invertir: vale 1.000.000 en total, se puso 1.050.000.
        inversiones.aportar(cocos.getId(), null, new BigDecimal("200000"), galicia, HOY, Origen.TEXTO, "aporte");
        inversiones.valuar(cocos.getId(), null, new BigDecimal("1000000"), HOY.toLocalDate());
        PanelInversiones.EstadoCuenta e = estado();
        assertThat(e.valor()).isEqualByComparingTo("1000000");
        assertThat(tenencia(null).valor()).isEqualByComparingTo("130000");
        assertThat(e.rendimiento()).isEqualByComparingTo("-50000");
        assertThat(e.porcentaje()).isEqualByComparingTo("-4.76");
        assertThat(e.actualizado()).isEqualTo(HOY.toLocalDate());
    }

    @Test
    void moverPlataNoCambiaElRendimientoYBorrarLoDeshace() {
        inversiones.valuar(cocos.getId(), spy, new BigDecimal("520000"), HOY.toLocalDate());
        BigDecimal antes = estado().rendimiento();

        // Compra de la bolsa a SPY: el total y el rendimiento no cambian.
        inversiones.aportar(cocos.getId(), null, new BigDecimal("100000"), galicia, HOY, Origen.TEXTO, "aporte");
        OperacionInversion compra = inversiones.operar(TipoOperacion.COMPRA, cocos.getId(), spy,
                new BigDecimal("100000"), HOY.toLocalDate(), "compré 100.000 de SPY");
        assertThat(tenencia(spy).valor()).isEqualByComparingTo("620000");
        assertThat(estado().rendimiento()).isEqualByComparingTo(antes);

        // Un retiro de una tenencia valuada deja un ajuste; borrarlo lo saca.
        inversiones.valuar(cocos.getId(), null, new BigDecimal("970000"), HOY.toLocalDate());
        Movimiento retiro = inversiones.retirar(cocos.getId(), spy, new BigDecimal("50000"), galicia, HOY,
                Origen.TEXTO, "saqué 50.000 de SPY");
        assertThat(estado().valor()).isEqualByComparingTo("920000");
        inversiones.borrarMovimiento(retiro.getId());
        // La venta de SPY a la bolsa sigue (es otra línea del historial): la bolsa vuelve a tener 50.000.
        assertThat(estado().aportado()).isEqualByComparingTo("950000");

        inversiones.borrarOperacion(compra.getId());
        assertThat(tenencia(spy).aportado()).isEqualByComparingTo("450000");
    }

    @Test
    void reservaParaUnaMeta() {
        ReservaInversion r = inversiones.reservar(cocos.getId(), "Bariloche", new BigDecimal("300000"));

        assertThat(r.isActiva()).isTrue();
        assertThat(estado().reservado()).isEqualByComparingTo("300000");
        assertThat(estado().libre()).isEqualByComparingTo("550000");

        // La misma meta otra vez la reemplaza; más de lo que vale, no.
        inversiones.reservar(cocos.getId(), "bariloche", new BigDecimal("400000"));
        assertThat(estado().reservas()).extracting(PanelInversiones.Reserva::monto)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("400000"));
        assertThatThrownBy(() -> inversiones.reservar(cocos.getId(), "Auto", new BigDecimal("500000")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inversiones.liberarMeta("Bariloche")).hasSize(1);
        assertThat(estado().reservado()).isEqualByComparingTo("0");
    }

    @Test
    void recordatorioConIgualQueAntes() {
        inversiones.valuar(cocos.getId(), spy, new BigDecimal("520000"), HOY.toLocalDate());
        assertThat(inversiones.recordatoriosPendientes()).isEmpty();

        reloj.ir(HOY.plusDays(7));
        List<InversionService.Recordatorio> pendientes = inversiones.recordatoriosPendientes();
        assertThat(pendientes).singleElement().satisfies(p -> {
            assertThat(p.cuenta()).isEqualTo("Cocos");
            assertThat(p.valor()).isEqualByComparingTo("870000");
            assertThat(p.dias()).isEqualTo(7);
        });

        inversiones.recordatorioEnviado(cocos.getId());
        assertThat(inversiones.recordatoriosPendientes()).isEmpty();

        List<ValuacionInversion> iguales = inversiones.igualQueAntes(cocos.getId(), HOY.plusDays(7).toLocalDate());
        assertThat(iguales).hasSize(2).allSatisfy(v -> assertThat(v.getOrigen()).isEqualTo(OrigenValuacion.IGUAL));
        assertThat(estado().valor()).isEqualByComparingTo("870000");
        assertThat(estado().diasSinActualizar()).isZero();

        // Apagado no pregunta más.
        reloj.ir(HOY.plusDays(30));
        inversiones.cambiarRecordatorio(cocos.getId(), null);
        assertThat(inversiones.recordatoriosPendientes()).isEmpty();
    }

    @Test
    void antesDeLaHoraNoSePregunta() {
        reloj.ir(HOY.plusDays(10).withHour(8));
        assertThat(inversiones.recordatoriosPendientes()).isEmpty();
    }
}
