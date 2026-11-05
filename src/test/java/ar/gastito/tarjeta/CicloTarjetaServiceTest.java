package ar.gastito.tarjeta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Optional;

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

    // La MasterCard Gold (la de siempre). La de Mercado Pago está en la base desde la migración, sin fechas.

    private long gold() {
        return tarjeta().getId();
    }

    private Optional<CicloTarjetaService.Panel> panel() {
        return ciclo.panel(gold()).filter(CicloTarjetaService.Panel::configurado);
    }

    private Optional<VistaResumenTarjeta> preguntaDeFechas() {
        return ciclo.preguntasDeFechas().stream().filter(p -> p.tarjetaId() == gold() && p.proximo() != null)
                .map(CicloTarjetaService.PreguntaFechas::proximo).findFirst();
    }

    private Optional<VistaResumenTarjeta> avisoDeVencimiento() {
        return ciclo.avisosDeVencimiento().stream().filter(r -> r.tarjetaId() == gold()).findFirst();
    }

    private Optional<VistaResumenTarjeta> preguntaDeDebito() {
        return ciclo.preguntasDeDebito().stream().filter(r -> r.tarjetaId() == gold()).findFirst();
    }

    private CicloTarjetaService.FechasCargadas cargarFechas(LocalDate cierre, LocalDate vencimiento) {
        return ciclo.cargarFechas(gold(), cierre, vencimiento);
    }

    private CicloTarjetaService.TotalCargado cargarTotal(Long resumenId, BigDecimal total) {
        return ciclo.cargarTotal(gold(), resumenId, total);
    }

    private CicloTarjetaService.Pago pagar(Long resumenId, BigDecimal monto, Origen origen, String texto) {
        return ciclo.pagar(gold(), resumenId, monto, origen, texto);
    }

    private void cierreReal() {
        cargarFechas(CIERRE, VENCIMIENTO);
    }

    // --- Fechas ---

    @Test
    void sinNingunCierreCargadoNoHayCiclo() {
        assertThat(panel()).isEmpty();
        assertThat(preguntaDeFechas()).isEmpty();
    }

    @Test
    void conElCierreRealQuedaPorPagarYElProximoEstimado() {
        cierreReal();

        CicloTarjetaService.Panel panel = panel().orElseThrow();

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
        assertThatThrownBy(() -> cargarFechas(VENCIMIENTO, CIERRE)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Asignación de compras y cuotas ---

    @Test
    void laCompraDelDiaDelCierreVaAEseResumenYLaDelDiaSiguienteAlProximo() {
        cierreReal();
        compra("10000", LocalDateTime.of(2026, 10, 29, 23, 30));
        compra("2500", LocalDateTime.of(2026, 10, 30, 0, 10));

        CicloTarjetaService.Panel panel = panel().orElseThrow();

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

        CicloTarjetaService.Panel panel = panel().orElseThrow();

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
        CicloTarjetaService alMesSiguiente = new CicloTarjetaService(resumenes, repositorio, planesRepo, catalogos, tarjetas,
                new CuotaProperties(CuotaProperties.PrimeraCuota.MES_SIGUIENTE),
                new TarjetaProperties(2, 3, 3, 9, BigDecimal.ONE, 6), reloj);

        CicloTarjetaService.Panel panel = alMesSiguiente.panel(gold()).orElseThrow();

        assertThat(panel.porPagar().get(0).cuotas()).isEqualByComparingTo("0");
        assertThat(panel.proximo().cuotas()).isEqualByComparingTo("10000");
        assertThat(panel.siguientes()).extracting(VistaResumenTarjeta::periodo)
                .containsExactly(YearMonth.of(2026, 12), YearMonth.of(2027, 1));
    }

    @Test
    void alConfirmarLasFechasLasComprasSeReasignanSolas() {
        cierreReal();
        compra("7000", LocalDateTime.of(2026, 11, 28, 12, 0));
        assertThat(panel().orElseThrow().proximo().compras()).isEqualByComparingTo("7000");

        CicloTarjetaService.FechasCargadas cargadas = cargarFechas(LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 12, 11));

        assertThat(cargadas.antes().cierre()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(cargadas.resumen().estimadas()).isFalse();
        CicloTarjetaService.Panel panel = panel().orElseThrow();
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
        assertThat(preguntaDeFechas()).isEmpty();

        reloj.ir(HOY);
        VistaResumenTarjeta proximo = preguntaDeFechas().orElseThrow();
        ciclo.preguntoFechas(proximo.id());
        assertThat(preguntaDeFechas()).isEmpty();

        reloj.ir(HOY.plusDays(1));
        assertThat(preguntaDeFechas()).isPresent();
    }

    @Test
    void noPreguntaAntesDeLaHoraDeLosAvisos() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 10, 31, 8, 59));

        assertThat(preguntaDeFechas()).isEmpty();
    }

    @Test
    void todaviaNoVuelveAPreguntarEnTresDias() {
        cierreReal();
        VistaResumenTarjeta proximo = preguntaDeFechas().orElseThrow();

        assertThat(ciclo.posponerFechas(proximo.id())).isEqualTo(LocalDate.of(2026, 11, 3));
        reloj.ir(LocalDateTime.of(2026, 11, 2, 10, 0));
        assertThat(preguntaDeFechas()).isEmpty();
        reloj.ir(LocalDateTime.of(2026, 11, 3, 10, 0));
        assertThat(preguntaDeFechas()).isPresent();
    }

    @Test
    void conLasFechasConfirmadasNoPregunta() {
        cierreReal();
        cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));

        assertThat(preguntaDeFechas()).isEmpty();
    }

    // --- Vencimiento y pago ---

    @Test
    void avisaTresDiasAntesDelVencimientoUnaSolaVez() {
        cierreReal();
        compra("10000", LocalDateTime.of(2026, 10, 20, 12, 0));
        reloj.ir(LocalDateTime.of(2026, 11, 7, 10, 0));
        assertThat(avisoDeVencimiento()).isEmpty();

        reloj.ir(LocalDateTime.of(2026, 11, 8, 10, 0));
        VistaResumenTarjeta aviso = avisoDeVencimiento().orElseThrow();
        assertThat(aviso.aDebitar()).isEqualByComparingTo("10000");
        ciclo.avisoVencimientoEnviado(aviso.id());

        reloj.ir(LocalDateTime.of(2026, 11, 9, 10, 0));
        assertThat(avisoDeVencimiento()).isEmpty();
    }

    @Test
    void conLaPcApagadaAvisaIgualAntesDelVencimiento() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 11, 10, 10, 0));

        assertThat(avisoDeVencimiento()).isPresent();
        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));
        assertThat(avisoDeVencimiento()).isEmpty();
    }

    @Test
    void elTotalRealQueDifiereDiceCuantoFaltaCargar() {
        cierreReal();
        compra("200000", LocalDateTime.of(2026, 10, 20, 12, 0));

        CicloTarjetaService.TotalCargado total = cargarTotal(null, new BigDecimal("250000"));

        assertThat(total.cargado()).isEqualByComparingTo("200000");
        assertThat(total.diferencia()).isEqualByComparingTo("50000");
        assertThat(total.coincide()).isFalse();
        assertThat(total.resumen().aDebitar()).isEqualByComparingTo("250000");
    }

    @Test
    void unaDiferenciaDentroDeLaToleranciaCoincide() {
        cierreReal();
        compra("200000", LocalDateTime.of(2026, 10, 20, 12, 0));

        assertThat(cargarTotal(null, new BigDecimal("200000.80")).coincide()).isTrue();
    }

    @Test
    void sinResumenCerradoNoHayDondeCargarElTotal() {
        cargarFechas(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 11));

        assertThatThrownBy(() -> cargarTotal(null, new BigDecimal("1000")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void elDiaDelVencimientoPreguntaSiSeDebitoUnaVezPorDia() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 11, 10, 10, 0));
        assertThat(preguntaDeDebito()).isEmpty();

        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));
        VistaResumenTarjeta resumen = preguntaDeDebito().orElseThrow();
        ciclo.preguntoDebito(resumen.id());
        assertThat(preguntaDeDebito()).isEmpty();

        reloj.ir(LocalDateTime.of(2026, 11, 12, 10, 0));
        assertThat(preguntaDeDebito()).isPresent();
    }

    @Test
    void elPagoDelResumenNoCuentaComoGastoYLoDejaPagado() {
        cierreReal();
        compra("200000", LocalDateTime.of(2026, 10, 20, 12, 0));
        cargarTotal(null, new BigDecimal("250000"));
        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));
        LocalDateTime noviembre = LocalDate.of(2026, 11, 1).atStartOfDay();
        BigDecimal gastosAntes = repositorio.totalGastos(noviembre, noviembre.plusMonths(1));

        CicloTarjetaService.Pago pago = pagar(null, null, Origen.AUTOMATICO, null);

        assertThat(pago.movimiento().getTipo()).isEqualTo(TipoMovimiento.PAGO_TARJETA);
        assertThat(pago.movimiento().getMonto()).isEqualByComparingTo("250000");
        assertThat(pago.movimiento().getCuenta().getNombre()).isEqualTo("Galicia");
        assertThat(pago.resumen().estado()).isEqualTo(EstadoResumenTarjeta.PAGADO);
        assertThat(repositorio.totalGastos(noviembre, noviembre.plusMonths(1))).isEqualByComparingTo(gastosAntes);
        assertThat(preguntaDeDebito()).isEmpty();
        assertThat(panel().orElseThrow().porPagar()).isEmpty();
        assertThatThrownBy(() -> pagar(pago.resumen().id(), null, Origen.AUTOMATICO, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sinTotalCargadoElPagoDichoConMontoQuedaComoTotalReal() {
        cierreReal();
        reloj.ir(LocalDateTime.of(2026, 11, 11, 10, 0));

        CicloTarjetaService.Pago pago = pagar(null, new BigDecimal("180000"), Origen.TEXTO,
                "se debitó la tarjeta, 180.000");

        assertThat(pago.resumen().totalReal()).isEqualByComparingTo("180000");
    }

    // --- Varias tarjetas: cada una con su ciclo ---

    private TarjetaCredito mercadoPago() {
        return tarjetas.findAll().stream().filter(t -> t.getNombre().equals("Mercado Pago")).findFirst()
                .orElseThrow();
    }

    private Movimiento compraCon(TarjetaCredito tarjeta, String monto, LocalDateTime cuando) {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Remera",
                ropa(), null, null, tarjeta, cuando), Origen.TEXTO, null);
        movimientos.confirmar(m.getId());
        return m;
    }

    /** La de Mercado Pago cerró el 25/10 y vence el 6/11 (ya cerrada, sin pagar). */
    private void cierreMercadoPago() {
        ciclo.cargarFechas(mercadoPago().getId(), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 6));
    }

    @Test
    void laMigracionDejaLaMasterCardGoldYLaDeMercadoPagoDesdeSuCuenta() {
        TarjetaCredito mp = mercadoPago();

        assertThat(tarjeta().getNombre()).isEqualTo("MasterCard Gold");
        assertThat(tarjeta().alias()).contains("master", "la de galicia");
        assertThat(mp.getCuentaDebito().getNombre()).isEqualTo("Mercado Pago");
        assertThat(mp.alias()).contains("la de mercado pago", "mp");
    }

    @Test
    void cadaTarjetaTieneSuCicloConSusCompras() {
        cierreReal();
        cierreMercadoPago();
        compra("7000", LocalDateTime.of(2026, 10, 30, 12, 0));
        compraCon(mercadoPago(), "3000", LocalDateTime.of(2026, 10, 30, 13, 0));
        compraCon(mercadoPago(), "1500", LocalDateTime.of(2026, 10, 20, 13, 0));

        CicloTarjetaService.Paneles paneles = ciclo.paneles();
        CicloTarjetaService.Panel gold = paneles.tarjetas().get(0);
        CicloTarjetaService.Panel mp = paneles.tarjetas().get(1);

        assertThat(gold.tarjeta()).isEqualTo("MasterCard Gold");
        assertThat(gold.proximo().compras()).isEqualByComparingTo("7000");
        assertThat(mp.tarjeta()).isEqualTo("Mercado Pago");
        assertThat(mp.proximo().compras()).isEqualByComparingTo("3000");
        assertThat(mp.proximo().cierre()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(mp.porPagar()).singleElement().satisfies(r -> {
            assertThat(r.cargado()).isEqualByComparingTo("1500");
            assertThat(r.vencimiento()).isEqualTo(LocalDate.of(2026, 11, 6));
        });
        assertThat(paneles.proximos()).isEqualByComparingTo("10000");
    }

    @Test
    void lasCuotasVanALosResumenesDeSuTarjeta() {
        cierreReal();
        cierreMercadoPago();
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("30000"),
                "Zapatillas", ropa(), null, null, mercadoPago(), LocalDateTime.of(2026, 10, 30, 12, 0)),
                Origen.TEXTO, null);
        planes.crear(m.getId(), 3, new BigDecimal("30000"));
        movimientos.confirmar(m.getId());
        planes.alGuardar(m.getId());

        CicloTarjetaService.Panel mp = ciclo.panel(mercadoPago().getId()).orElseThrow();

        assertThat(mp.proximo().cuotas()).isEqualByComparingTo("10000");
        assertThat(mp.siguientes()).extracting(VistaResumenTarjeta::cuotas)
                .allSatisfy(c -> assertThat(c).isEqualByComparingTo("10000"));
        assertThat(panel().orElseThrow().proximo().cuotas()).isEqualByComparingTo("0");
    }

    @Test
    void laTarjetaSinNingunCierrePideLasFechasYTodaviaNoLaPospone() {
        cierreReal();
        long mp = mercadoPago().getId();

        assertThat(ciclo.panel(mp).orElseThrow().configurado()).isFalse();
        assertThat(ciclo.preguntasDeFechas()).filteredOn(p -> p.tarjetaId() == mp).singleElement()
                .satisfies(p -> {
                    assertThat(p.proximo()).isNull();
                    assertThat(p.clave()).isEqualTo(-mp);
                });

        ciclo.preguntoFechas(-mp);
        assertThat(ciclo.preguntasDeFechas()).noneMatch(p -> p.tarjetaId() == mp);

        reloj.ir(HOY.plusDays(1));
        assertThat(ciclo.preguntasDeFechas()).anyMatch(p -> p.tarjetaId() == mp);
        LocalDate hasta = ciclo.posponerFechas(-mp);
        assertThat(hasta).isEqualTo(LocalDate.of(2026, 11, 4));
        reloj.ir(HOY.plusDays(2));
        assertThat(ciclo.preguntasDeFechas()).noneMatch(p -> p.tarjetaId() == mp);
        reloj.ir(HOY.plusDays(4));
        assertThat(ciclo.preguntasDeFechas()).anyMatch(p -> p.tarjetaId() == mp);
    }

    @Test
    void elPagoDeCadaResumenSaleDeLaCuentaDeSuTarjeta() {
        cierreReal();
        cierreMercadoPago();
        compraCon(mercadoPago(), "85000", LocalDateTime.of(2026, 10, 20, 13, 0));
        reloj.ir(LocalDateTime.of(2026, 11, 6, 10, 0));

        assertThat(ciclo.preguntasDeDebito()).extracting(VistaResumenTarjeta::tarjeta)
                .containsExactly("Mercado Pago");
        CicloTarjetaService.Pago pago = ciclo.pagar(mercadoPago().getId(), null, null, Origen.TEXTO,
                "pagué la tarjeta de mercado pago");

        assertThat(pago.movimiento().getTipo()).isEqualTo(TipoMovimiento.PAGO_TARJETA);
        assertThat(pago.movimiento().getMonto()).isEqualByComparingTo("85000");
        assertThat(pago.movimiento().getCuenta().getNombre()).isEqualTo("Mercado Pago");
        assertThat(pago.movimiento().getTarjeta().getNombre()).isEqualTo("Mercado Pago");
        assertThat(panel().orElseThrow().porPagar()).singleElement()
                .extracting(VistaResumenTarjeta::estado).isEqualTo(EstadoResumenTarjeta.CERRADO);
    }

    @Test
    void elPagoDeUnaTarjetaSinResumenIgualSaleDeSuCuenta() {
        Movimiento pago = ciclo.pagarSinResumen(mercadoPago().getId(), new BigDecimal("85000"), Origen.TEXTO,
                "pagué la tarjeta de mercado pago, 85.000");

        assertThat(pago.getTipo()).isEqualTo(TipoMovimiento.PAGO_TARJETA);
        assertThat(pago.getCuenta().getNombre()).isEqualTo("Mercado Pago");
    }
}
