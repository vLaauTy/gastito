package ar.gastito.tarjeta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.catalogo.TarjetaCreditoRepository;
import ar.gastito.cuota.CuotaProperties;
import ar.gastito.cuota.PlanCuotasRepository;
import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;
import ar.gastito.resumen.RelojDePrueba;

/**
 * Hoy es el sábado 31/10/2026 a las 10. El resumen real cerró el jueves 29/10 y vence el miércoles 11/11; el
 * próximo se estima para el lunes 30/11 (el 29 es domingo) y vence el lunes 14/12.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CicloTarjetaService.class, MovimientoService.class, PlanCuotasService.class, CatalogoService.class,
        CicloTarjetaServiceTest.Config.class})
class CicloTarjetaServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 31, 10, 0);
    static final LocalDate CIERRE = LocalDate.of(2026, 10, 29);
    static final LocalDate VENCIMIENTO = LocalDate.of(2026, 11, 11);

    @TestConfiguration
    static class Config {

        @Bean
        RelojDePrueba clock() {
            return new RelojDePrueba(HOY, ARGENTINA);
        }

        @Bean
        CuotaProperties cuotaProperties() {
            return new CuotaProperties(CuotaProperties.PrimeraCuota.MISMO_MES);
        }

        @Bean
        TarjetaProperties tarjetaProperties() {
            return new TarjetaProperties(2, 3, 3, 9, BigDecimal.ONE, 6);
        }
    }

    @Autowired
    CicloTarjetaService ciclo;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    PlanCuotasService planes;

    @Autowired
    MovimientoRepository repositorio;

    @Autowired
    ResumenTarjetaRepository resumenes;

    @Autowired
    PlanCuotasRepository planesRepo;

    @Autowired
    CatalogoService catalogos;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    TarjetaCreditoRepository tarjetas;

    @Autowired
    RelojDePrueba reloj;

    @BeforeEach
    void volverAHoy() {
        reloj.ir(HOY);
    }

    private TarjetaCredito tarjeta() {
        return tarjetas.findAll().get(0);
    }

    private Categoria ropa() {
        return categorias.findByNombre("Ropa").orElseThrow();
    }

    private Movimiento compra(String monto, LocalDateTime cuando) {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Remera",
                ropa(), null, null, tarjeta(), cuando), Origen.TEXTO, null);
        movimientos.confirmar(m.getId());
        return m;
    }

    private void cuotas(String total, int cantidad, LocalDateTime cuando) {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(total),
                "Zapatillas", ropa(), null, null, tarjeta(), cuando), Origen.TEXTO, null);
        planes.crear(m.getId(), cantidad, new BigDecimal(total));
        movimientos.confirmar(m.getId());
        planes.alGuardar(m.getId());
    }

    private void cierreReal() {
        ciclo.cargarFechas(CIERRE, VENCIMIENTO);
    }

    // --- Fechas ---

    @Test
    void sinNingunCierreCargadoNoHayCiclo() {
        assertThat(ciclo.panel()).isEmpty();
        assertThat(ciclo.preguntaDeFechas()).isEmpty();
    }

    @Test
    void conElCierreRealQuedaPorPagarYElProximoEstimado() {
        cierreReal();

        CicloTarjetaService.Panel panel = ciclo.panel().orElseThrow();

        assertThat(panel.porPagar()).singleElement().satisfies(r -> {
            assertThat(r.vencimiento()).isEqualTo(VENCIMIENTO);
            assertThat(r.estimadas()).isFalse();
            assertThat(r.estado()).isEqualTo(EstadoResumenTarjeta.CERRADO);
        });
        assertThat(panel.proximo().cierre()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(panel.proximo().vencimiento()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(panel.proximo().estimadas()).isTrue();
        assertThat(panel.proximo().estado()).isEqualTo(EstadoResumenTarjeta.ABIERTO);
        assertThat(panel.cuentaDebito()).isEqualTo("Galicia");
    }

    @Test
    void elVencimientoTieneQueSerDespuesDelCierre() {
        assertThatThrownBy(() -> ciclo.cargarFechas(VENCIMIENTO, CIERRE)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Asignación de compras y cuotas ---

    @Test
    void laCompraDelDiaDelCierreVaAEseResumenYLaDelDiaSiguienteAlProximo() {
        cierreReal();
        compra("10000", LocalDateTime.of(2026, 10, 29, 23, 30));
        compra("2500", LocalDateTime.of(2026, 10, 30, 0, 10));

        CicloTarjetaService.Panel panel = ciclo.panel().orElseThrow();

        assertThat(panel.porPagar().get(0).cargado()).isEqualByComparingTo("10000");
        assertThat(panel.proximo().compras()).isEqualByComparingTo("2500");
        assertThat(panel.proximo().cantidadCompras()).isEqualTo(1);
    }

    @Test
    void lasCuotasVanUnaPorResumenDesdeElDeLaCompra() {
        cierreReal();
        // Comprada el 28/10 (antes del cierre): cuota 1 en octubre, 2 en noviembre, 3 en diciembre.
        cuotas("30000", 3, LocalDateTime.of(2026, 10, 28, 12, 0));
        compra("5000", LocalDateTime.of(2026, 11, 2, 12, 0));

        CicloTarjetaService.Panel panel = ciclo.panel().orElseThrow();

        assertThat(panel.porPagar().get(0).cuotas()).isEqualByComparingTo("10000");
        assertThat(panel.proximo().compras()).isEqualByComparingTo("5000");
        assertThat(panel.proximo().cuotas()).isEqualByComparingTo("10000");
        assertThat(panel.proximo().cargado()).isEqualByComparingTo("15000");
        assertThat(panel.siguientes()).singleElement().satisfies(r -> {
            assertThat(r.periodo()).isEqualTo(YearMonth.of(2026, 12));
            assertThat(r.id()).isNull();
            assertThat(r.cuotas()).isEqualByComparingTo("10000");
            assertThat(r.detalle()).singleElement().extracting(VistaResumenTarjeta.Linea::cuota).isEqualTo(3);
        });
    }

    @Test
    void conPrimeraCuotaAlMesSiguienteLaCuota1VaAlResumenQueSigue() {
        cierreReal();
        cuotas("30000", 3, LocalDateTime.of(2026, 10, 28, 12, 0));
        CicloTarjetaService alMesSiguiente = new CicloTarjetaService(resumenes, repositorio, planesRepo, catalogos,
                new CuotaProperties(CuotaProperties.PrimeraCuota.MES_SIGUIENTE),
                new TarjetaProperties(2, 3, 3, 9, BigDecimal.ONE, 6), reloj);

        CicloTarjetaService.Panel panel = alMesSiguiente.panel().orElseThrow();

        assertThat(panel.porPagar().get(0).cuotas()).isEqualByComparingTo("0");
        assertThat(panel.proximo().cuotas()).isEqualByComparingTo("10000");
        assertThat(panel.siguientes()).extracting(VistaResumenTarjeta::periodo)
                .containsExactly(YearMonth.of(2026, 12), YearMonth.of(2027, 1));
    }

    @Test
    void alConfirmarLasFechasLasComprasSeReasignanSolas() {
        cierreReal();
        compra("7000", LocalDateTime.of(2026, 11, 28, 12, 0));
        assertThat(ciclo.panel().orElseThrow().proximo().compras()).isEqualByComparingTo("7000");

        CicloTarjetaService.FechasCargadas cargadas = ciclo.cargarFechas(LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 12, 11));

        assertThat(cargadas.antes().cierre()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(cargadas.resumen().estimadas()).isFalse();
        CicloTarjetaService.Panel panel = ciclo.panel().orElseThrow();
        assertThat(panel.proximo().cierre()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(panel.proximo().compras()).isEqualByComparingTo("0");
        assertThat(ciclo.resumenDe(repositorio.findAll().stream()
                .filter(m -> m.getMonto().compareTo(new BigDecimal("7000")) == 0).findFirst().orElseThrow()))
                .hasValueSatisfying(f -> assertThat(f.vencimiento()).isEqualTo(LocalDate.of(2027, 1, 12)));
    }

    @Test
    void laTarjetaDeUnaCompraDiceEnQueResumenEntra() {
        cierreReal();
        Movimiento m = compra("2500", LocalDateTime.of(2026, 10, 31, 9, 0));

        assertThat(ciclo.resumenDe(m)).hasValueSatisfying(f -> {
            assertThat(f.vencimiento()).isEqualTo(LocalDate.of(2026, 12, 14));
            assertThat(f.estimadas()).isTrue();
        });
    }

    // --- Recordatorio de fechas ---

    @Test
    void preguntaPorLasFechasDosDiasDespuesDelCierreUnaVezPorDia() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 10, 30, 10, 0));
        assertThat(ciclo.preguntaDeFechas()).isEmpty();

        reloj.ir(HOY);
        VistaResumenTarjeta proximo = ciclo.preguntaDeFechas().orElseThrow();
        ciclo.preguntoFechas(proximo.id());
        assertThat(ciclo.preguntaDeFechas()).isEmpty();

        reloj.ir(HOY.plusDays(1));
        assertThat(ciclo.preguntaDeFechas()).isPresent();
    }

    @Test
    void noPreguntaAntesDeLaHoraDeLosAvisos() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 10, 31, 8, 59));

        assertThat(ciclo.preguntaDeFechas()).isEmpty();
    }

    @Test
    void todaviaNoVuelveAPreguntarEnTresDias() {
        cierreReal();
        VistaResumenTarjeta proximo = ciclo.preguntaDeFechas().orElseThrow();

        assertThat(ciclo.posponerFechas(proximo.id())).isEqualTo(LocalDate.of(2026, 11, 3));
        reloj.ir(LocalDateTime.of(2026, 11, 2, 10, 0));
        assertThat(ciclo.preguntaDeFechas()).isEmpty();
        reloj.ir(LocalDateTime.of(2026, 11, 3, 10, 0));
        assertThat(ciclo.preguntaDeFechas()).isPresent();
    }

    @Test
    void conLasFechasConfirmadasNoPregunta() {
        cierreReal();
        ciclo.cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));

        assertThat(ciclo.preguntaDeFechas()).isEmpty();
    }

    // --- Vencimiento y pago ---

    @Test
    void avisaTresDiasAntesDelVencimientoUnaSolaVez() {
        cierreReal();
        compra("10000", LocalDateTime.of(2026, 10, 20, 12, 0));
        reloj.ir(LocalDateTime.of(2026, 11, 7, 10, 0));
        assertThat(ciclo.avisoDeVencimiento()).isEmpty();

        reloj.ir(LocalDateTime.of(2026, 11, 8, 10, 0));
        VistaResumenTarjeta aviso = ciclo.avisoDeVencimiento().orElseThrow();
        assertThat(aviso.aDebitar()).isEqualByComparingTo("10000");
        ciclo.avisoVencimientoEnviado(aviso.id());

        reloj.ir(LocalDateTime.of(2026, 11, 9, 10, 0));
        assertThat(ciclo.avisoDeVencimiento()).isEmpty();
    }

    @Test
    void conLaPcApagadaAvisaIgualAntesDelVencimiento() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 11, 10, 10, 0));

        assertThat(ciclo.avisoDeVencimiento()).isPresent();
        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));
        assertThat(ciclo.avisoDeVencimiento()).isEmpty();
    }

    @Test
    void elTotalRealQueDifiereDiceCuantoFaltaCargar() {
        cierreReal();
        compra("200000", LocalDateTime.of(2026, 10, 20, 12, 0));

        CicloTarjetaService.TotalCargado total = ciclo.cargarTotal(null, new BigDecimal("250000"));

        assertThat(total.cargado()).isEqualByComparingTo("200000");
        assertThat(total.diferencia()).isEqualByComparingTo("50000");
        assertThat(total.coincide()).isFalse();
        assertThat(total.resumen().aDebitar()).isEqualByComparingTo("250000");
    }

    @Test
    void unaDiferenciaDentroDeLaToleranciaCoincide() {
        cierreReal();
        compra("200000", LocalDateTime.of(2026, 10, 20, 12, 0));

        assertThat(ciclo.cargarTotal(null, new BigDecimal("200000.80")).coincide()).isTrue();
    }

    @Test
    void sinResumenCerradoNoHayDondeCargarElTotal() {
        ciclo.cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));

        assertThatThrownBy(() -> ciclo.cargarTotal(null, new BigDecimal("1000")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void elDiaDelVencimientoPreguntaSiSeDebitoUnaVezPorDia() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 11, 10, 10, 0));
        assertThat(ciclo.preguntaDeDebito()).isEmpty();

        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));
        VistaResumenTarjeta resumen = ciclo.preguntaDeDebito().orElseThrow();
        ciclo.preguntoDebito(resumen.id());
        assertThat(ciclo.preguntaDeDebito()).isEmpty();

        reloj.ir(LocalDateTime.of(2026, 11, 12, 10, 0));
        assertThat(ciclo.preguntaDeDebito()).isPresent();
    }

    @Test
    void elPagoDelResumenNoCuentaComoGastoYLoDejaPagado() {
        cierreReal();
        compra("200000", LocalDateTime.of(2026, 10, 20, 12, 0));
        ciclo.cargarTotal(null, new BigDecimal("250000"));
        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));
        LocalDateTime noviembre = LocalDate.of(2026, 11, 1).atStartOfDay();
        BigDecimal gastosAntes = repositorio.totalGastos(noviembre, noviembre.plusMonths(1));

        CicloTarjetaService.Pago pago = ciclo.pagar(null, null, Origen.AUTOMATICO, null);

        assertThat(pago.movimiento().getTipo()).isEqualTo(TipoMovimiento.PAGO_TARJETA);
        assertThat(pago.movimiento().getMonto()).isEqualByComparingTo("250000");
        assertThat(pago.movimiento().getCuenta().getNombre()).isEqualTo("Galicia");
        assertThat(pago.resumen().estado()).isEqualTo(EstadoResumenTarjeta.PAGADO);
        assertThat(repositorio.totalGastos(noviembre, noviembre.plusMonths(1))).isEqualByComparingTo(gastosAntes);
        assertThat(ciclo.preguntaDeDebito()).isEmpty();
        assertThat(ciclo.panel().orElseThrow().porPagar()).isEmpty();
        assertThatThrownBy(() -> ciclo.pagar(pago.resumen().id(), null, Origen.AUTOMATICO, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sinTotalCargadoElPagoDichoConMontoQuedaComoTotalReal() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));

        CicloTarjetaService.Pago pago = ciclo.pagar(null, new BigDecimal("180000"), Origen.TEXTO,
                "se debitó la tarjeta, 180.000");

        assertThat(pago.resumen().totalReal()).isEqualByComparingTo("180000");
    }
}
