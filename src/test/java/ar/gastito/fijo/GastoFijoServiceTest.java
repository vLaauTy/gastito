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

import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.TarjetaCreditoRepository;
import ar.gastito.fijo.GastoFijoService.EstadoChecklist;
import ar.gastito.fijo.GastoFijoService.ItemChecklist;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.RelojDePrueba;

/** Se arranca el domingo 25/10/2026 a las 10:00; el reloj se mueve en cada test. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({GastoFijoService.class, GastoFijoServiceTest.Config.class})
class GastoFijoServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 25, 10, 0);

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
    MovimientoRepository movimientos;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    TarjetaCreditoRepository tarjetas;

    @Autowired
    RelojDePrueba reloj;

    @BeforeEach
    void volverAHoy() {
        reloj.ir(HOY); // el reloj es del contexto: otro test lo pudo haber movido
    }

    private VistaFijo seguro(int dia) {
        return fijos.alta(new GastoFijoService.NuevoFijo("Seguro de la moto", new BigDecimal("32900"),
                categorias.findByNombre("Seguros").orElseThrow(), null, null, tarjetas.findAll().get(0), dia,
                Frecuencia.MENSUAL, false));
    }

    private VistaFijo luz(Frecuencia frecuencia) {
        return fijos.alta(new GastoFijoService.NuevoFijo("Luz", new BigDecimal("28000"),
                categorias.findByNombre("Servicios (luz, gas, servicios sociales)").orElseThrow(), null, null, null, 27,
                frecuencia, true));
    }

    /** Un fijo con checklist mensual: no propone monto, el usuario lo carga y se vincula por nombre o alias. */
    private VistaFijo luzConChecklist(int dia, String alias) {
        return fijos.alta(new GastoFijoService.NuevoFijo("Luz", new BigDecimal("28000"),
                categorias.findByNombre("Servicios (luz, gas, servicios sociales)").orElseThrow(), null, null, null,
                dia, Frecuencia.MENSUAL, true, alias, false));
    }

    private void ir(int anio, int mes, int dia) {
        reloj.ir(LocalDate.of(anio, mes, dia).atTime(10, 0));
    }

    private Movimiento confirmarElCobro(String monto) {
        List<Movimiento> cobros = fijos.generarCobros();
        assertThat(cobros).hasSize(1);
        return fijos.confirmar(cobros.get(0).getId(), monto == null ? null : new BigDecimal(monto)).movimiento();
    }

    @Test
    void elAltaEmpiezaEsteMesSiElDiaNoPasoYSiNoElQueViene() {
        assertThat(seguro(28).proximoCobro()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(seguro(4).proximoCobro()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(fijos.generarCobros()).isEmpty(); // no se crean cobros para atrás
    }

    @Test
    void elDiaDelCobroSeCreaPendienteConLaMarcaDeFijoYUnaSolaVez() {
        VistaFijo f = seguro(28);
        ir(2026, 10, 27);
        assertThat(fijos.generarCobros()).isEmpty();

        ir(2026, 10, 28);
        List<Movimiento> cobros = fijos.generarCobros();

        assertThat(cobros).singleElement().satisfies(m -> {
            assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.PENDIENTE);
            assertThat(m.getMonto()).isEqualByComparingTo("32900");
            assertThat(m.getDescripcion()).isEqualTo("Seguro de la moto");
            assertThat(m.getCategoria().getNombre()).isEqualTo("Seguros");
            assertThat(m.getTarjeta()).isNotNull();
            assertThat(m.getGastoFijoId()).isEqualTo(f.id());
            assertThat(m.getFijoPeriodo()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(m.getFechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 28, 9, 0));
            assertThat(m.getOrigen()).isEqualTo(Origen.AUTOMATICO);
        });
        assertThat(fijos.generarCobros()).isEmpty();
        assertThat(fijos.cobrosPendientes()).hasSize(1);
    }

    @Test
    void conLaPcApagadaAlArrancarSeCreanLosCobrosQueFaltaron() {
        seguro(28);
        ir(2026, 12, 30); // apagada desde el 25/10: faltan octubre, noviembre y diciembre

        assertThat(fijos.generarCobros()).extracting(Movimiento::getFijoPeriodo).containsExactly(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1));
    }

    @Test
    void unBimestralSaltaUnMes() {
        luz(Frecuencia.BIMESTRAL);
        ir(2027, 1, 27);

        assertThat(fijos.generarCobros()).extracting(Movimiento::getFijoPeriodo)
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1));
    }

    @Test
    void esteMesNoNoSeVuelveAPedirYUnoDadoDeBajaNoSeCobra() {
        VistaFijo f = seguro(28);
        ir(2026, 10, 28);
        fijos.esteMesNo(fijos.generarCobros().get(0).getId());

        assertThat(fijos.generarCobros()).isEmpty();
        ir(2026, 11, 28);
        fijos.darDeBaja(f.id());
        assertThat(fijos.generarCobros()).isEmpty();
        assertThat(fijos.activos()).isEmpty();
    }

    @Test
    void unVariableProponeElPromedioDeLosUltimosTresCobros() {
        luz(Frecuencia.MENSUAL);
        ir(2026, 10, 27);
        confirmarElCobro("27000");
        ir(2026, 11, 27);
        confirmarElCobro("31000");
        ir(2026, 12, 27);
        confirmarElCobro("33500");
        ir(2027, 1, 27);
        confirmarElCobro("40000");

        ir(2027, 2, 27);
        // (31.000 + 33.500 + 40.000) / 3 = 34.833,33 → 34.833
        assertThat(fijos.generarCobros()).singleElement()
                .satisfies(m -> assertThat(m.getMonto()).isEqualByComparingTo("34833"));
    }

    @Test
    void unAumentoDeMasDel5PorCientoSeAvisaYQuedaComoElMontoEsperado() {
        VistaFijo f = seguro(28);
        ir(2026, 10, 28);
        confirmarElCobro(null);
        ir(2026, 11, 28);
        Movimiento noviembre = fijos.generarCobros().get(0);

        GastoFijoService.Cobro cobro = fijos.confirmar(noviembre.getId(), new BigDecimal("36100"));

        assertThat(cobro.aumento()).get().satisfies(a -> {
            assertThat(a.nombre()).isEqualTo("Seguro de la moto");
            assertThat(a.anterior()).isEqualByComparingTo("32900");
            assertThat(a.nuevo()).isEqualByComparingTo("36100");
            assertThat(a.porcentaje()).isEqualByComparingTo("9.7");
        });
        assertThat(fijos.vista(f.id()).montoEsperado()).isEqualByComparingTo("36100");
        // Diciembre propone el monto nuevo y, si se confirma igual, no hay aviso.
        ir(2026, 12, 28);
        Movimiento diciembre = fijos.generarCobros().get(0);
        assertThat(diciembre.getMonto()).isEqualByComparingTo("36100");
        assertThat(fijos.confirmar(diciembre.getId(), null).aumento()).isEmpty();
    }

    @Test
    void elPrimerCobroNoTieneConQueCompararse() {
        seguro(28);
        ir(2026, 10, 28);

        assertThat(fijos.confirmar(fijos.generarCobros().get(0).getId(), new BigDecimal("50000")).aumento()).isEmpty();
    }

    @Test
    void paraLaEtapa7ElResumenDeLaTarjetaConfirmaElCobroAunqueNoSeHayaGenerado() {
        VistaFijo f = seguro(28);

        // El 25/10 el cobro de octubre todavía no se generó: se crea y se confirma con el monto del resumen.
        GastoFijoService.Cobro cobro = fijos.confirmarDesdeResumen(f.id(), YearMonth.of(2026, 10),
                new BigDecimal("32900"));

        assertThat(cobro.movimiento().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(cobro.movimiento().getFijoPeriodo()).isEqualTo(LocalDate.of(2026, 10, 1));
        ir(2026, 10, 28);
        assertThat(fijos.generarCobros()).isEmpty(); // ya está
        // Si ya estaba confirmado, queda como está.
        assertThat(fijos.confirmarDesdeResumen(f.id(), YearMonth.of(2026, 10), new BigDecimal("99999")).movimiento()
                .getMonto()).isEqualByComparingTo("32900");
    }

    @Test
    void unMontoQueNoSirveSeRechaza() {
        seguro(28);
        ir(2026, 10, 28);
        Long id = fijos.generarCobros().get(0).getId();

        assertThatThrownBy(() -> fijos.confirmar(id, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Checklist mensual, vínculo y carga manual (extra de la etapa 6) ---

    @Test
    void unFijoSinProponerMontoNoGeneraCobrosAutomaticos() {
        luzConChecklist(27, "edesur");
        ir(2026, 10, 27);

        assertThat(fijos.generarCobros()).isEmpty();
    }

    @Test
    void elVinculoEncuentraPorNombreOAliasSinAcentosNiMayusculas() {
        luzConChecklist(27, "edesur, factura de luz");
        seguro(28);

        assertThat(fijos.vincular("pagué 70000 de EDESUR")).get().extracting(GastoFijo::getNombre)
                .isEqualTo("Luz");
        assertThat(fijos.vincular("90000 en luz")).isPresent();
        assertThat(fijos.vincular("la factura de luz llegó cara")).isPresent();
        assertThat(fijos.vincular("32900 el seguro de la moto")).get().extracting(GastoFijo::getNombre)
                .isEqualTo("Seguro de la moto");
        assertThat(fijos.vincular("3700 en pan y papas")).isEmpty();
    }

    @Test
    void cargarManualConfirmaElGastoDelMesYAvisaSiYaHabiaUnoCargado() {
        VistaFijo f = luzConChecklist(27, "edesur");

        GastoFijoService.Carga primera = fijos.cargarManual(f.id(), new BigDecimal("28000"), HOY, Origen.TEXTO,
                "pagué 28000 de luz");
        assertThat(primera.movimiento().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(primera.movimiento().getGastoFijoId()).isEqualTo(f.id());
        assertThat(primera.movimiento().getFijoPeriodo()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(primera.yaCargado()).isEmpty();

        // Otro pago el mismo mes: se guarda igual (puede ser otra factura) pero se avisa.
        GastoFijoService.Carga segunda = fijos.cargarManual(f.id(), new BigDecimal("5000"), HOY, Origen.TEXTO,
                "5000 más de luz");
        assertThat(segunda.yaCargado()).get().isEqualTo(primera.movimiento());
    }

    @Test
    void elChecklistMuestraFaltaCargadoYEsteMesNo() {
        VistaFijo luz = luzConChecklist(27, "edesur");
        VistaFijo seguro = seguro(28); // bimestral no: mensual con flujo automático

        ir(2026, 10, 15);
        assertThat(fijos.checklist(YearMonth.of(2026, 10))).hasSize(2)
                .anySatisfy(i -> assertThat(i).satisfies(item -> {
                    assertThat(item.fijoId()).isEqualTo(luz.id());
                    assertThat(item.estado()).isEqualTo(EstadoChecklist.FALTA);
                    assertThat(item.vencimiento()).isEqualTo(LocalDate.of(2026, 10, 27));
                }))
                .anySatisfy(i -> assertThat(i.fijoId()).isEqualTo(seguro.id()));

        fijos.cargarManual(luz.id(), new BigDecimal("31000"), LocalDateTime.of(2026, 10, 20, 10, 0), Origen.TEXTO,
                "31000 de luz");
        ItemChecklist cargado = fijos.checklist(YearMonth.of(2026, 10)).stream()
                .filter(i -> i.fijoId().equals(luz.id())).findFirst().orElseThrow();
        assertThat(cargado.estado()).isEqualTo(EstadoChecklist.CARGADO);
        assertThat(cargado.monto()).isEqualByComparingTo("31000");

        ir(2026, 11, 1);
        fijos.esteMesNoDelPeriodo(luz.id(), YearMonth.of(2026, 11));
        ItemChecklist esteMesNo = fijos.checklist(YearMonth.of(2026, 11)).stream()
                .filter(i -> i.fijoId().equals(luz.id())).findFirst().orElseThrow();
        assertThat(esteMesNo.estado()).isEqualTo(EstadoChecklist.ESTE_MES_NO);

        // Diciembre: la referencia es el último cargado (octubre), no el "este mes no" de noviembre.
        ItemChecklist diciembre = fijos.checklist(YearMonth.of(2026, 12)).stream()
                .filter(i -> i.fijoId().equals(luz.id())).findFirst().orElseThrow();
        assertThat(diciembre.montoAnterior()).isEqualByComparingTo("31000");
    }

    @Test
    void deshacerVuelveAFaltaYNoSePuedeSobreUnCobroAutomaticoPendiente() {
        VistaFijo luz = luzConChecklist(27, "edesur");
        ir(2026, 11, 1);
        fijos.esteMesNoDelPeriodo(luz.id(), YearMonth.of(2026, 11));

        fijos.deshacer(luz.id(), YearMonth.of(2026, 11));
        assertThat(fijos.checklist(YearMonth.of(2026, 11)).stream().filter(i -> i.fijoId().equals(luz.id()))
                .findFirst().orElseThrow().estado()).isEqualTo(EstadoChecklist.FALTA);

        VistaFijo seguro = seguro(28); // el reloj ya está en noviembre: el primer cobro es el 28/11
        ir(2026, 11, 28);
        Long cobroId = fijos.generarCobros().get(0).getId();
        assertThatThrownBy(() -> fijos.deshacer(seguro.id(), YearMonth.of(2026, 11)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fijos.cobrosPendientes()).extracting(Movimiento::getId).containsExactly(cobroId);
    }
}
