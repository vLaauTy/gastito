package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
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

import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.MedioPagoRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({IngresoService.class, MovimientoService.class, InicioRegistro.class, InicioRegistroTest.Reloj.class})
class InicioRegistroTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 10, 27, 10, 0).atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    InicioRegistro inicio;

    @Autowired
    IngresoService ingresos;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    private void gasto(LocalDateTime fecha) {
        Movimiento m = movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal("500"), "test",
                categorias.findAll().get(0), List.of(), medios.findAll().get(0), null, null, fecha), Origen.TEXTO,
                "test");
        movimientos.confirmar(m.getId());
    }

    @Test
    void sinGastosNoHayInicioAunqueHayaIngresos() {
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("1000"), null, null, LocalDate.of(2026, 5, 20));

        assertThat(inicio.primerDia()).isEmpty();
        assertThat(inicio.hayDatosDesde(LocalDate.of(2026, 6, 1))).isFalse();
    }

    @Test
    void elInicioEsElPrimerGastoYNoUnIngresoMasViejo() {
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("1000"), null, null, LocalDate.of(2026, 5, 20));
        gasto(LocalDateTime.of(2026, 8, 3, 12, 0));

        assertThat(inicio.primerDia()).contains(LocalDate.of(2026, 8, 3));
        // Los meses con solo sueldo cargado salen "sin datos", no como $0 gastado.
        assertThat(inicio.hayDatosDesde(LocalDate.of(2026, 6, 1))).isFalse();
        assertThat(inicio.hayDatosDesde(LocalDate.of(2026, 9, 1))).isTrue();
    }
}
