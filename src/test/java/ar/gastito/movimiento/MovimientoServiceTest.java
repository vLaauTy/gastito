package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({MovimientoService.class, MovimientoServiceTest.Reloj.class})
class MovimientoServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    /** 15/10/2026 16:42 en Argentina. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 15, 16, 42);

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    MovimientoService service;

    @Autowired
    CategoriaRepository categorias;

    @Test
    void alConfirmarDevuelveElTotalDelMesEnLaCategoria() {
        Categoria kiosco = categoria("Kiosco");
        confirmado(kiosco, "1500", AHORA.minusDays(3));
        confirmado(kiosco, "999", AHORA.minusMonths(1)); // otro mes: no suma
        pendiente(kiosco, "5000", AHORA); // pendiente: no suma
        service.cancelar(pendiente(kiosco, "7000", AHORA).getId()); // cancelado: no suma
        confirmado(categoria("Ropa"), "20000", AHORA); // otra categoría: no suma

        Movimiento nuevo = pendiente(kiosco, "3700", AHORA);
        MovimientoService.Confirmacion confirmacion = service.confirmar(nuevo.getId());

        assertThat(confirmacion.movimiento().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(confirmacion.totalMesCategoria()).isEqualByComparingTo("5200");
    }

    @Test
    void elTotalEsDelMesDelMovimientoNoDelMesActual() {
        Categoria kiosco = categoria("Kiosco");
        confirmado(kiosco, "1000", LocalDateTime.of(2026, 9, 10, 10, 0));

        Movimiento deSeptiembre = pendiente(kiosco, "500", LocalDateTime.of(2026, 9, 30, 22, 0));

        assertThat(service.confirmar(deSeptiembre.getId()).totalMesCategoria()).isEqualByComparingTo("1500");
    }

    @Test
    void noSePuedeConfirmarDosVeces() {
        Movimiento m = pendiente(categoria("Kiosco"), "1500", AHORA);
        service.confirmar(m.getId());

        assertThatThrownBy(() -> service.confirmar(m.getId())).isInstanceOf(MovimientoNoPendienteException.class);
        assertThatThrownBy(() -> service.cancelar(m.getId())).isInstanceOf(MovimientoNoPendienteException.class);
    }

    @Test
    void resumenDeHoy() {
        Categoria kiosco = categoria("Kiosco");
        confirmado(kiosco, "1500", AHORA.withHour(9));
        confirmado(kiosco, "200.50", AHORA.withHour(23).withMinute(59));
        confirmado(kiosco, "9999", AHORA.minusDays(1)); // ayer
        pendiente(kiosco, "8888", AHORA); // pendiente

        MovimientoService.ResumenDia hoy = service.hoy();

        assertThat(hoy.gastos()).hasSize(2);
        assertThat(hoy.total()).isEqualByComparingTo("1700.50");
    }

    @Test
    void resumenDelMesPorCategoria() {
        confirmado(categoria("Kiosco"), "1500", AHORA.withDayOfMonth(1).withHour(0).withMinute(0));
        confirmado(categoria("Kiosco"), "500", AHORA);
        confirmado(categoria("Supermercado"), "30000", AHORA);
        confirmado(categoria("Supermercado"), "1", AHORA.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0));

        MovimientoService.ResumenMes mes = service.mesActual();

        assertThat(mes.porCategoria()).extracting(TotalPorCategoria::categoria)
                .containsExactly("Supermercado", "Kiosco");
        assertThat(mes.porCategoria().get(1).total()).isEqualByComparingTo("2000");
        assertThat(mes.total()).isEqualByComparingTo("32000");
    }

    @Test
    void sinGastosLosTotalesDanCero() {
        assertThat(service.hoy().total()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(service.mesActual().porCategoria()).isEmpty();
    }

    private Categoria categoria(String nombre) {
        return categorias.findByNombre(nombre).orElseThrow();
    }

    private Movimiento pendiente(Categoria categoria, String monto, LocalDateTime fecha) {
        MovimientoInterpretado interpretado = new MovimientoInterpretado(new BigDecimal(monto), "test", categoria,
                null, null, null, fecha);
        return service.crearPendiente(interpretado, Origen.TEXTO, "test");
    }

    private Movimiento confirmado(Categoria categoria, String monto, LocalDateTime fecha) {
        Movimiento m = pendiente(categoria, monto, fecha);
        service.confirmar(m.getId());
        return m;
    }
}
