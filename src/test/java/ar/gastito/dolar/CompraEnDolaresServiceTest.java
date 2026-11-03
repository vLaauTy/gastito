package ar.gastito.dolar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.bot.FormatoMensajes;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.cuota.CuotaProperties;
import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoEspecialException;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.RelojDePrueba;
import ar.gastito.tarjeta.CicloTarjetaService;
import ar.gastito.tarjeta.TarjetaProperties;
import ar.gastito.tarjeta.VistaResumenTarjeta;

/**
 * Compras en dólares con la tarjeta: los pesos estimados con la cotización de referencia (o sin estimar), la
 * corrección con lo que cobraron, los totales en pesos y el ciclo de la tarjeta. Hoy es el sábado 31/10/2026 a las
 * 10; la tarjeta cerró el 29/10 y vence el 11/11 (el próximo resumen cierra ≈ 30/11).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CompraEnDolaresService.class, MovimientoService.class, CatalogoService.class, CicloTarjetaService.class,
        PlanCuotasService.class, CompraEnDolaresServiceTest.Config.class})
class CompraEnDolaresServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 31, 10, 0);

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
    CompraEnDolaresService compras;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    MovimientoRepository repositorio;

    @Autowired
    CicloTarjetaService ciclo;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @Autowired
    RelojDePrueba reloj;

    private final FormatoMensajes formato = new FormatoMensajes(new RelojDePrueba(HOY, ARGENTINA));

    @BeforeEach
    void volverAHoy() {
        reloj.ir(HOY);
    }

    private Categoria suscripciones() {
        return categorias.findByNombre("Suscripciones").orElseThrow();
    }

    /** "Pagué 20 dólares de Steam": pendiente, con el número en dólares todavía como monto, sin medio. */
    private Movimiento pendiente(String dolares, String descripcion, MedioPago medio) {
        return movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(dolares), descripcion,
                suscripciones(), medio, null, null, HOY.minusHours(1)), Origen.TEXTO, null);
    }

    private Movimiento compraGuardada(String dolares, String descripcion) {
        Movimiento m = compras.pasarADolares(pendiente(dolares, descripcion, null).getId(), new BigDecimal(dolares));
        movimientos.confirmar(m.getId());
        repositorio.flush();
        return m;
    }

    private MedioPago debito() {
        return medios.findAll().stream().filter(m -> m.getNombre().equals("Débito")).findFirst().orElseThrow();
    }

    // --- Pesos estimados ---

    @Test
    void conCotizacionLosPesosSeEstimanYVaConLaTarjeta() {
        compras.cargarCotizacion(new BigDecimal("1500"));

        Movimiento m = compraGuardada("20", "Steam");

        assertThat(m.isEnDolares()).isTrue();
        assertThat(m.getMontoOriginal()).isEqualByComparingTo("20");
        assertThat(m.getMonto()).isEqualByComparingTo("30000");
        assertThat(m.getCotizacionUsada()).isEqualByComparingTo("1500");
        assertThat(m.isMontoEstimado()).isTrue();
        assertThat(m.getMedioPago().esTarjetaDeCredito()).isTrue();
        assertThat(m.getTarjeta()).isNotNull();
        assertThat(formato.monto(m)).isEqualTo("US$ 20 ≈ $30.000 (estimado)");
    }

    @Test
    void sinCotizacionSeGuardaEnCeroSinEstimar() {
        Movimiento m = compraGuardada("9.99", "Spotify");

        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(m.getMonto()).isEqualByComparingTo("0");
        assertThat(m.isSinEstimar()).isTrue();
        assertThat(m.getCotizacionUsada()).isNull();
        assertThat(formato.monto(m)).isEqualTo("US$ 9,99 ≈ sin estimar");
    }

    @Test
    void alCargarLaCotizacionSeEstimanLasQueEstabanSinEstimarYLasDemasNoCambian() {
        compras.cargarCotizacion(new BigDecimal("1400"));
        Movimiento conCotizacion = compraGuardada("10", "Netflix");
        Movimiento sinEstimar = compras.pasarADolares(pendiente("20", "Steam", null).getId(), new BigDecimal("20"));
        sinEstimar.estimarPesos(null);
        movimientos.confirmar(sinEstimar.getId());

        CompraEnDolaresService.CotizacionCargada cargada = compras.cargarCotizacion(new BigDecimal("1500"));

        assertThat(cargada.estimadas()).extracting(Movimiento::getId).containsExactly(sinEstimar.getId());
        assertThat(repositorio.findById(sinEstimar.getId()).orElseThrow().getMonto()).isEqualByComparingTo("30000");
        assertThat(repositorio.findById(conCotizacion.getId()).orElseThrow().getMonto())
                .isEqualByComparingTo("14000");
        assertThat(compras.referencia().orElseThrow().getValor()).isEqualByComparingTo("1500");
    }

    @Test
    void conOtroMedioDePagoNoSePasaADolares() {
        Movimiento m = pendiente("20", "Steam", debito());
        Movimiento enMemoria = repositorio.findById(m.getId()).orElseThrow();

        assertThatThrownBy(() -> enMemoria.pasarADolares(new BigDecimal("20"), BigDecimal.TEN))
                .isInstanceOf(MovimientoEspecialException.class);
    }

    @Test
    void unaCompraEnDolaresNoSePasaAOtroMedio() {
        Movimiento m = compraGuardada("20", "Steam");

        assertThatThrownBy(() -> movimientos.corregir(m.getId(), null, null, null, null, debito().getId()))
                .isInstanceOf(MovimientoEspecialException.class);
    }

    // --- Corregir con el resumen ---

    @Test
    void corregirConElMontoRealDejaDeSerEstimadoYGuardaLaCotizacionReal() {
        compras.cargarCotizacion(new BigDecimal("1500"));
        Movimiento m = compraGuardada("20", "Steam");

        Movimiento corregida = compras.corregirPesos(m.getId(), new BigDecimal("31200"));
        repositorio.flush();

        assertThat(corregida.getMonto()).isEqualByComparingTo("31200");
        assertThat(corregida.isMontoEstimado()).isFalse();
        assertThat(corregida.getCotizacionUsada()).isEqualByComparingTo("1560");
        assertThat(corregida.getMontoOriginal()).isEqualByComparingTo("20");
        assertThat(formato.monto(corregida)).isEqualTo("US$ 20 = $31.200");
    }

    @Test
    void loDeSteamSalio31200CorrigeLaCompraYCancelaElGastoNuevo() {
        Movimiento compra = compraGuardada("20", "Steam");
        Movimiento nuevo = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("31200"),
                "Steam", suscripciones(), null, null, null, HOY), Origen.TEXTO, "lo de Steam salió 31.200");

        assertThat(compras.estimadaParecida(nuevo)).map(Movimiento::getId).contains(compra.getId());

        CompraEnDolaresService.Correccion c = compras.corregirCon(nuevo.getId(), compra.getId());

        assertThat(c.anterior()).isEqualByComparingTo("0");
        assertThat(c.compra().getMonto()).isEqualByComparingTo("31200");
        assertThat(c.compra().isMontoEstimado()).isFalse();
        assertThat(repositorio.findById(nuevo.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoMovimiento.CANCELADO);
        assertThat(compras.estimadaParecida(nuevo)).isEmpty();
    }

    @Test
    void unGastoConOtraDescripcionNoSeConfundeConLaCompraEnDolares() {
        compraGuardada("20", "Steam");
        Movimiento otro = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("31200"),
                "Netflix", suscripciones(), null, null, null, HOY), Origen.TEXTO, null);

        assertThat(compras.estimadaParecida(otro)).isEmpty();
    }

    // --- Totales ---

    @Test
    void losTotalesSumanLosPesosNoLosDolares() {
        compras.cargarCotizacion(new BigDecimal("1500"));
        compraGuardada("20", "Steam");
        Movimiento kiosco = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("1000"),
                "Kiosco", categorias.findByNombre("Kiosco").orElseThrow(), null, null, null, HOY.minusHours(2)),
                Origen.TEXTO, null);
        movimientos.confirmar(kiosco.getId());
        repositorio.flush();

        assertThat(movimientos.hoy().total()).isEqualByComparingTo("31000");
        assertThat(movimientos.mesActual().total()).isEqualByComparingTo("31000");
    }

    // --- Ciclo de la tarjeta ---

    @Test
    void elProximoResumenMuestraAparteLasComprasEnDolares() {
        ciclo.cargarFechas(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11));
        compras.cargarCotizacion(new BigDecimal("1500"));
        compraGuardada("20", "Steam");
        Movimiento sinEstimar = compras.pasarADolares(pendiente("5", "iCloud", null).getId(), new BigDecimal("5"));
        sinEstimar.estimarPesos(null);
        movimientos.confirmar(sinEstimar.getId());
        repositorio.flush();

        VistaResumenTarjeta proximo = ciclo.panel().orElseThrow().proximo();

        assertThat(proximo.dolares()).isEqualByComparingTo("25");
        assertThat(proximo.dolaresEnPesos()).isEqualByComparingTo("30000");
        assertThat(proximo.dolaresEstimados()).isTrue();
        assertThat(proximo.dolaresSinEstimar()).isEqualTo(1);
        assertThat(proximo.compras()).isEqualByComparingTo("30000");
        assertThat(proximo.detalle()).filteredOn(l -> l.dolares() != null).hasSize(2);
    }

    @Test
    void corregidaYaNoCuentaComoEstimadaEnElResumen() {
        ciclo.cargarFechas(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 11));
        compras.cargarCotizacion(new BigDecimal("1500"));
        Movimiento m = compraGuardada("20", "Steam");
        compras.corregirPesos(m.getId(), new BigDecimal("31200"));
        repositorio.flush();

        VistaResumenTarjeta proximo = ciclo.panel().orElseThrow().proximo();

        assertThat(proximo.dolaresEstimados()).isFalse();
        assertThat(proximo.dolaresEnPesos()).isEqualByComparingTo("31200");
        assertThat(proximo.cargado()).isEqualByComparingTo("31200");
    }
}
