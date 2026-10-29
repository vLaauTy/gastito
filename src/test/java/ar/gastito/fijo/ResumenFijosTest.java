package ar.gastito.fijo;

import static org.assertj.core.api.Assertions.assertThat;

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

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.cuota.CuotaProperties;
import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/** Hoy es el domingo 25/10/2026: los fijos con día del 26 en adelante empiezan en octubre. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ResumenFijos.class, GastoFijoService.class, PlanCuotasService.class, MovimientoService.class,
        IngresoService.class, ResumenFijosTest.Config.class})
class ResumenFijosTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime HOY = LocalDateTime.of(2026, 10, 25, 10, 0);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(HOY.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }

        @Bean
        CuotaProperties cuotaProperties() {
            return new CuotaProperties(CuotaProperties.PrimeraCuota.MISMO_MES);
        }
    }

    @Autowired
    ResumenFijos resumen;

    @Autowired
    GastoFijoService fijos;

    @Autowired
    PlanCuotasService cuotas;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    IngresoService ingresos;

    @Autowired
    CategoriaRepository categorias;

    private Categoria servicios() {
        return categorias.findByNombre("Servicios (luz, gas, servicios sociales)").orElseThrow();
    }

    private VistaFijo fijo(String nombre, String monto, int dia, Frecuencia frecuencia, boolean variable) {
        return fijos.alta(new GastoFijoService.NuevoFijo(nombre, monto == null ? null : new BigDecimal(monto),
                servicios(), null, null, null, dia, frecuencia, variable));
    }

    private void cobro(VistaFijo f, String monto, int anio, int mes) {
        fijos.cargarManual(f.id(), new BigDecimal(monto), LocalDate.of(anio, mes, 10).atTime(9, 0), Origen.TEXTO,
                null);
    }

    /** Zapatillas en 3 cuotas de $10.000: octubre, noviembre y diciembre. */
    private void cuotasDeZapatillas() {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("30000"),
                "Zapatillas", categorias.findByNombre("Ropa").orElseThrow(), null, null, null,
                LocalDateTime.of(2026, 10, 20, 18, 0)), Origen.TEXTO, "zapatillas en 3 cuotas");
        cuotas.crear(m.getId(), 3, new BigDecimal("30000"));
        movimientos.confirmar(m.getId());
        cuotas.alGuardar(m.getId());
    }

    @Test
    void estimadoEsPagadoMasLoQueFaltaConSuMontoDeReferencia() {
        cobro(fijo("Seguro", "32900", 28, Frecuencia.MENSUAL, false), "33000", 2026, 10); // pagado, monto real
        VistaFijo luz = fijo("Luz", "28000", 27, Frecuencia.MENSUAL, true); // variable: promedio de 3 anteriores
        cobro(luz, "27000", 2026, 7);
        cobro(luz, "30000", 2026, 8);
        cobro(luz, "33000", 2026, 9);
        cobro(luz, "24000", 2026, 6); // el cuarto para atrás no entra en el promedio
        fijo("Internet", "15000", 30, Frecuencia.MENSUAL, false); // falta, con el aproximado del fijo
        fijo("Alarma", null, 29, Frecuencia.MENSUAL, false); // falta, sin monto
        VistaFijo gimnasio = fijo("Gimnasio", "20000", 26, Frecuencia.MENSUAL, false);
        fijos.esteMesNoDelPeriodo(gimnasio.id(), OCTUBRE); // no cuenta

        ResumenFijos.ResumenDelMes r = resumen.delMes(OCTUBRE);

        assertThat(r.pagado()).isEqualByComparingTo("33000");
        assertThat(r.pagados()).isEqualTo(1);
        assertThat(r.falta()).isEqualByComparingTo("45000"); // luz 30.000 + internet 15.000
        assertThat(r.faltan()).isEqualTo(3);
        assertThat(r.sinMonto()).isEqualTo(1);
        assertThat(r.estimado()).isEqualByComparingTo("78000");
        assertThat(r.cuotas()).isEqualByComparingTo("0");
        assertThat(r.comprometido()).isEqualByComparingTo("78000");
        assertThat(r.ingresos()).isNull();
        assertThat(r.porcentajeIngresos()).isNull();
    }

    @Test
    void elComprometidoSumaLasCuotasDelMesYSeComparaConLosIngresos() {
        fijo("Internet", "15000", 30, Frecuencia.MENSUAL, false);
        cuotasDeZapatillas();
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("80000"), null, null, LocalDate.of(2026, 10, 5));

        ResumenFijos.ResumenDelMes r = resumen.delMes(OCTUBRE);

        assertThat(r.cuotas()).isEqualByComparingTo("10000");
        assertThat(r.cantidadCuotas()).isEqualTo(1);
        assertThat(r.comprometido()).isEqualByComparingTo("25000");
        assertThat(r.ingresos()).isEqualByComparingTo("80000");
        assertThat(r.porcentajeIngresos()).isEqualTo(31); // 31,25 %
        // En noviembre, la cuota 2 y el fijo otra vez.
        assertThat(resumen.delMes(OCTUBRE.plusMonths(1)).cuotas()).isEqualByComparingTo("10000");
    }

    @Test
    void unBimestralQueNoTocaEseMesNoSuma() {
        fijo("Agua", "12000", 28, Frecuencia.BIMESTRAL, false); // octubre, diciembre…

        assertThat(resumen.delMes(OCTUBRE).estimado()).isEqualByComparingTo("12000");
        ResumenFijos.ResumenDelMes noviembre = resumen.delMes(OCTUBRE.plusMonths(1));
        assertThat(noviembre.estimado()).isEqualByComparingTo("0");
        assertThat(noviembre.faltan()).isZero();
    }

    @Test
    void unVariableSinCobrosAnterioresUsaElMontoDelAlta() {
        fijo("Gas", "18000", 28, Frecuencia.MENSUAL, true);

        assertThat(resumen.delMes(OCTUBRE).falta()).isEqualByComparingTo("18000");
    }
}
