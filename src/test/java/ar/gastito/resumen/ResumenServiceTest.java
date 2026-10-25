package ar.gastito.resumen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.CalculadoraCostoIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.MovimientoService;

/** El armado y los envíos automáticos, con la IA simulada. Hoy es el jueves 22/10/2026. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ResumenService.class, CalculoResumen.class, MovimientoService.class, IngresoService.class,
        RegistroUsoIa.class, CalculadoraCostoIa.class, JacksonAutoConfiguration.class, ResumenServiceTest.Config.class})
class ResumenServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 22, 10, 0);

    @TestConfiguration
    static class Config {
        @Bean
        RelojDePrueba clock() {
            return new RelojDePrueba(HOY, ARGENTINA);
        }

        @Bean
        GastitoProperties properties() {
            return new GastitoProperties(ARGENTINA, null,
                    new GastitoProperties.Ia("x", null, "claude-haiku-5-5", 1024, Map.of()), null, null,
                    new GastitoProperties.Resumenes(LocalTime.of(21, 0), LocalTime.of(9, 0), "claude-haiku-5-5",
                            "claude-haiku-5-5", 1024, new BigDecimal("3000"), List.of("Kiosco"),
                            new GastitoProperties.Minimo(5, 3), new GastitoProperties.Minimo(15, 7)),
                    null);
        }
    }

    @MockitoBean
    RedactorResumen redactor;

    @Autowired
    ResumenService service;

    @Autowired
    RelojDePrueba reloj;

    @Autowired
    ResumenRepository resumenes;

    @Autowired
    EnvioResumenRepository envios;

    @BeforeEach
    void laIaRedacta() {
        reloj.ir(HOY); // el reloj es del contexto: otro test lo pudo haber movido
        when(redactor.redactar(any())).thenReturn(new RedactorResumen.Redaccion(
                List.of("Una observación."), "Un consejo.", false, null));
    }

    // --- Armar ---

    @Test
    void elResumenPedidoEsLaSemanaEnCursoYQuedaParcial() {
        ResumenService.Armado a = service.pedido(TipoResumen.SEMANAL);

        Resumen r = a.resumen();
        assertThat(r.getId()).isNotNull();
        assertThat(r.getDesde()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(r.getHasta()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(r.isParcial()).isTrue();
        assertThat(r.isAutomatico()).isFalse();
        assertThat(r.getObservaciones()).containsExactly("Una observación.");
        assertThat(r.getConsejo()).isEqualTo("Un consejo.");
        assertThat(r.getDatos()).contains("\"totalGastado\"").contains("\"2026-10-19\"");
        assertThat(service.datos(r)).isEqualTo(a.datos());
        assertThat(service.ultimo()).get().extracting(u -> u.resumen().getId()).isEqualTo(r.getId());
    }

    @Test
    void laIaNoVeLasNotasYSinGastosNoHayConsejo() {
        Resumen r = service.pedido(TipoResumen.SEMANAL).resumen();

        assertThat(r.getDatos()).contains("\"notas\"");
        verify(redactor).redactar(argThat(p -> !p.datosJson().contains("notas") && p.datosJson().contains("pocosDatos")
                && !p.conConsejo()));
    }

    @Test
    void unResumenSinTextoDeLaIaQuedaSoloConLosNumeros() {
        when(redactor.redactar(any()))
                .thenReturn(new RedactorResumen.Redaccion(List.of(), null, true, null));

        Resumen r = service.armar(PeriodoResumen.mesDe(LocalDate.of(2026, 9, 1)), true).resumen();

        assertThat(r.isParcial()).isFalse();
        assertThat(r.isTextoIaDescartado()).isTrue();
        assertThat(r.getObservaciones()).isEmpty();
        assertThat(r.getConsejo()).isNull();
    }

    // --- Envíos automáticos ---

    @Test
    void laPrimeraVezNoMandaNadaDeAntes() {
        assertThat(service.pendientes(HOY)).isEmpty();
        assertThat(envios.findAll()).extracting(EnvioResumen::getTipo, EnvioResumen::getDesde, EnvioResumen::getEstado)
                .containsExactlyInAnyOrder(
                        tuple(TipoResumen.SEMANAL, LocalDate.of(2026, 10, 12),
                                EnvioResumen.Estado.OMITIDO),
                        tuple(TipoResumen.MENSUAL, LocalDate.of(2026, 9, 1),
                                EnvioResumen.Estado.OMITIDO));
    }

    @Test
    void elDomingoALas21SaleElDeLaSemanaYNoSeRepite() {
        service.pendientes(HOY); // primer arranque

        assertThat(service.pendientes(LocalDateTime.of(2026, 10, 25, 20, 59))).isEmpty();
        List<ResumenService.Envio> domingo = service.pendientes(LocalDateTime.of(2026, 10, 25, 21, 0));
        reloj.ir(LocalDateTime.of(2026, 10, 25, 21, 0));
        assertThat(domingo).singleElement().satisfies(e -> {
            assertThat(e.periodo()).isEqualTo(PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 19)));
            assertThat(e.anteriores()).isEmpty();
        });

        service.registrarEnvio(domingo.get(0), service.armar(domingo.get(0).periodo(), true).resumen());

        assertThat(service.pendientes(LocalDateTime.of(2026, 10, 25, 21, 1))).isEmpty();
        assertThat(service.pendientes(LocalDateTime.of(2026, 10, 26, 8, 0))).isEmpty();
    }

    @Test
    void elDiaUnoALasNueveSaleElDelMes() {
        service.pendientes(HOY);

        List<ResumenService.Envio> pendientes = service.pendientes(LocalDateTime.of(2026, 11, 1, 9, 0));

        // También vence la semana del 26/10 al 1/11, que termina ese domingo... a las 21.
        assertThat(pendientes).extracting(e -> e.periodo().tipo(), e -> e.periodo().desde())
                .containsExactly(
                        tuple(TipoResumen.SEMANAL, LocalDate.of(2026, 10, 19)),
                        tuple(TipoResumen.MENSUAL, LocalDate.of(2026, 10, 1)));
    }

    @Test
    void conLaPcApagadaVaElMasRecienteYLosOtrosQuedanEnVerAnteriores() {
        service.pendientes(HOY); // semana del 12/10 omitida
        reloj.ir(LocalDateTime.of(2026, 11, 10, 8, 0));

        // Prendida recién el martes 10/11: vencieron las semanas del 19/10, 26/10 y 2/11, y el mes de octubre.
        List<ResumenService.Envio> pendientes = service.pendientes(LocalDateTime.of(2026, 11, 10, 8, 0));

        ResumenService.Envio semanal = pendientes.get(0);
        assertThat(semanal.periodo().desde()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(semanal.anteriores()).extracting(PeriodoResumen::desde)
                .containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26));
        assertThat(ResumenService.ofrecidos(semanal)).isEqualTo(2);
        assertThat(pendientes.get(1).periodo().desde()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(pendientes.get(1).anteriores()).isEmpty();

        service.registrarEnvio(semanal, service.armar(semanal.periodo(), true).resumen());
        assertThat(service.pendientes(LocalDateTime.of(2026, 11, 10, 8, 1))).extracting(e -> e.periodo().tipo())
                .containsExactly(TipoResumen.MENSUAL); // el semanal ya no está pendiente

        // "Ver anteriores": arma las dos semanas, de la más vieja a la más nueva, y quedan enviadas.
        List<ResumenService.Armado> anteriores = service.anteriores(TipoResumen.SEMANAL, LocalDate.of(2026, 10, 19),
                LocalDate.of(2026, 10, 26));
        assertThat(anteriores).extracting(a -> a.resumen().getDesde())
                .containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26));
        assertThat(anteriores).allSatisfy(a -> assertThat(resumenes.findById(a.resumen().getId()).orElseThrow()
                .getEnviadoEn()).isNotNull());
        assertThat(envios.findByTipoAndDesde(TipoResumen.SEMANAL, LocalDate.of(2026, 10, 19)).orElseThrow()
                .getEstado()).isEqualTo(EnvioResumen.Estado.ENVIADO);
        // Tocar el botón otra vez no los manda de nuevo.
        assertThat(service.anteriores(TipoResumen.SEMANAL, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)))
                .isEmpty();
    }

    @Test
    void masDeDoceAnterioresSoloSeOfrecenLosUltimos() {
        service.pendientes(HOY);
        reloj.ir(LocalDateTime.of(2027, 3, 1, 10, 0));
        List<ResumenService.Envio> pendientes = service.pendientes(LocalDateTime.of(2027, 3, 1, 10, 0));
        ResumenService.Envio semanal = pendientes.get(0);
        assertThat(semanal.anteriores()).hasSizeGreaterThan(12);

        service.registrarEnvio(semanal, service.armar(semanal.periodo(), true).resumen());

        assertThat(ResumenService.ofrecidos(semanal)).isEqualTo(12);
        assertThat(envios.findAll()).filteredOn(e -> e.getEstado() == EnvioResumen.Estado.OFRECIDO).hasSize(12);
    }
}
