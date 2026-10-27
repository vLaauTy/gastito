package ar.gastito.cuota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.catalogo.TarjetaCreditoRepository;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.RelojDePrueba;

/** La compra fue el 20/10/2026; el reloj se mueve en cada test. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PlanCuotasService.class, MovimientoService.class, PlanCuotasServiceTest.Config.class})
class PlanCuotasServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime COMPRA = LocalDateTime.of(2026, 10, 20, 18, 0);

    @TestConfiguration
    static class Config {

        @Bean
        RelojDePrueba clock() {
            return new RelojDePrueba(COMPRA, ARGENTINA);
        }

        @Bean
        CuotaProperties cuotaProperties() {
            return new CuotaProperties(CuotaProperties.PrimeraCuota.MISMO_MES);
        }
    }

    @Autowired
    PlanCuotasService planes;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    MovimientoRepository repositorio;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    TarjetaCreditoRepository tarjetasRepo;

    @Autowired
    RelojDePrueba reloj;

    @BeforeEach
    void volverAHoy() {
        reloj.ir(COMPRA); // el reloj es del contexto: otro test lo pudo haber movido
    }

    private Categoria ropa() {
        return categorias.findByNombre("Ropa").orElseThrow();
    }

    private TarjetaCredito tarjeta() {
        return tarjetasRepo.findAll().get(0);
    }

    /** "zapatillas 84000 en 6 cuotas con la tarjeta". */
    private Movimiento zapatillas(String total, int cuotas) {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(total),
                "Zapatillas", ropa(), null, null, tarjeta(), COMPRA), Origen.TEXTO, "zapatillas en cuotas");
        return planes.crear(m.getId(), cuotas, new BigDecimal(total));
    }

    @Test
    void crearDejaLaCuota1EtiquetadaConElValorDeCadaCuota() {
        Movimiento cuota1 = zapatillas("84000", 6);

        assertThat(cuota1.getMonto()).isEqualByComparingTo("14000");
        assertThat(cuota1.getCuotaActual()).isEqualTo(1);
        assertThat(cuota1.getCuotaTotal()).isEqualTo(6);
        assertThat(cuota1.getDescripcion()).endsWith("(cuota 1 de 6)");
    }

    @Test
    void alGuardarGeneraLasCuotasRestantesYaConfirmadasConLaDescripcionNumerada() {
        Movimiento cuota1 = zapatillas("84000", 6);
        movimientos.confirmar(cuota1.getId());

        List<Movimiento> siguientes = planes.alGuardar(cuota1.getId());

        assertThat(siguientes).hasSize(5);
        assertThat(siguientes).allMatch(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO);
        assertThat(siguientes).extracting(Movimiento::getCuotaActual).containsExactly(2, 3, 4, 5, 6);
        assertThat(siguientes).allMatch(m -> m.getMonto().compareTo(new BigDecimal("14000")) == 0);
        assertThat(siguientes.get(0).getDescripcion()).endsWith("(cuota 2 de 6)");
        assertThat(siguientes.get(0).getFechaHora()).isEqualTo(LocalDateTime.of(2026, 11, 20, 18, 0));
        assertThat(siguientes.get(4).getFechaHora()).isEqualTo(LocalDateTime.of(2027, 3, 20, 18, 0));
    }

    @Test
    void alGuardarResincronizaValorCuotaYCategoriaDesdeElMovimientoConfirmado() {
        Movimiento cuota1 = zapatillas("84000", 6);
        movimientos.cambiarMonto(cuota1.getId(), new BigDecimal("90000"));
        Categoria otraCategoria = categorias.findByNombre("Otros").orElseThrow();
        movimientos.elegirCategoria(cuota1.getId(), otraCategoria.getId());
        movimientos.confirmar(cuota1.getId());

        List<Movimiento> siguientes = planes.alGuardar(cuota1.getId());

        assertThat(siguientes).allMatch(m -> m.getMonto().compareTo(new BigDecimal("90000")) == 0);
        assertThat(siguientes).allMatch(m -> m.getCategoria().getId() == otraCategoria.getId());
    }

    @Test
    void cancelarBorraSoloLasCuotasFuturasYDejaElPlanCancelado() {
        Movimiento cuota1 = zapatillas("60000", 6);
        Long planId = cuota1.getPlanCuotasId();
        movimientos.confirmar(cuota1.getId());
        planes.alGuardar(cuota1.getId());

        VistaPlanCuotas v = planes.cancelar(planId);

        assertThat(v.activo()).isFalse();
        assertThat(repositorio.findByPlanCuotasIdOrderByCuotaActual(planId)).extracting(Movimiento::getCuotaActual)
                .containsExactly(1); // solo la cuota 1 (noviembre en adelante son "futuras" desde la compra de hoy)
    }

    @Test
    void cancelarUnPlanQueYaTerminoNoFallaYNoBorraNada() {
        Movimiento cuota1 = zapatillas("20000", 2);
        Long planId = cuota1.getPlanCuotasId();
        movimientos.confirmar(cuota1.getId());
        planes.alGuardar(cuota1.getId());
        reloj.ir(LocalDateTime.of(2027, 1, 1, 9, 0)); // ya pasaron las dos cuotas (octubre y noviembre)

        planes.cancelar(planId);

        assertThat(repositorio.findByPlanCuotasIdOrderByCuotaActual(planId)).hasSize(2);
    }

    @Test
    void cancelarUnPlanYaCanceladoFalla() {
        Movimiento cuota1 = zapatillas("20000", 2);
        Long planId = cuota1.getPlanCuotasId();
        movimientos.confirmar(cuota1.getId());
        planes.alGuardar(cuota1.getId());
        planes.cancelar(planId);

        assertThatThrownBy(() -> planes.cancelar(planId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activosSoloTraeLosPlanesConAlMenosUnaCuotaGuardada() {
        Movimiento cancelado = zapatillas("20000", 2); // se "cancela" el gasto: la cuota 1 nunca se confirma
        repositorio.delete(repositorio.findById(cancelado.getId()).orElseThrow());

        assertThat(planes.activos()).isEmpty();
    }
}
