package ar.gastito.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
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

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.InicioRegistro;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/** Cada herramienta contra H2: septiembre y octubre de 2026. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({HerramientasConsulta.class, CatalogoService.class, IngresoService.class, MovimientoService.class,
        InicioRegistro.class,
        HerramientasConsultaTest.Reloj.class})
class HerramientasConsultaTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);
    static final String SEVEN_UP = "7791813777021";

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 10, 25, 10, 0).atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    HerramientasConsulta herramientas;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    IngresoService ingresos;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @Autowired
    CuentaRepository cuentas;

    @BeforeEach
    void datos() {
        gasto("Kiosco", "Kiosco", "1500", LocalDate.of(2026, 10, 3), "Efectivo", null);
        gasto("Kiosco", "Alfajor", "900", LocalDate.of(2026, 10, 10), "QR", "Mercado Pago");
        gasto("Transporte / Moto", "Nafta", "12000", LocalDate.of(2026, 10, 12), "Tarjeta de crédito", null);
        ticket("Día", "8000", LocalDate.of(2026, 10, 15), List.of(
                new ItemTicket("Seven Up lata 354 ml", "SEVEN UP LATA", SEVEN_UP, new BigDecimal("2"),
                        new BigDecimal("2250"), new BigDecimal("4500")),
                new ItemTicket("Galletitas", null, null, new BigDecimal("1"), new BigDecimal("3500"),
                        new BigDecimal("3500")),
                new ItemTicket("20% ClubDía", null, null, new BigDecimal("-900"))));
        ticket("Día", "2250", LocalDate.of(2026, 10, 20), List.of(
                new ItemTicket("Seven Up", null, SEVEN_UP, new BigDecimal("1"), new BigDecimal("2250"),
                        new BigDecimal("2250"))));
        gasto("Kiosco", "Kiosco", "2000", LocalDate.of(2026, 9, 5), "Efectivo", null);
        gasto("Supermercado", "Coto", "30000", LocalDate.of(2026, 9, 20), "Débito", "Galicia");
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), null, null, OCT_1);
        ingresos.cargar(ClaseIngreso.EXTRA, new BigDecimal("25000"), "Arreglo de PC", null, LocalDate.of(2026, 10, 12));
    }

    @Test
    void totalGastadoConYSinFiltros() {
        assertThat(herramientas.totalGastado(OCT_1, OCT_31, HerramientasConsulta.Filtros.NINGUNO))
                .satisfies(t -> {
                    assertThat(t.total()).isEqualByComparingTo("24650");
                    assertThat(t.cantidad()).isEqualTo(5);
                });
        assertThat(herramientas.totalGastado(OCT_1, OCT_31, new HerramientasConsulta.Filtros("kiosco", null, null,
                null)).total()).isEqualByComparingTo("2400");
        assertThat(herramientas.totalGastado(OCT_1, OCT_31, new HerramientasConsulta.Filtros(null, "QR",
                "Mercado Pago", null)).total()).isEqualByComparingTo("900");
        // El comercio se busca en la descripción y en el comercio del ticket, sin tildes.
        assertThat(herramientas.totalGastado(LocalDate.of(2026, 9, 1), OCT_31, new HerramientasConsulta.Filtros(null,
                null, null, "dia")).total()).isEqualByComparingTo("10250");
        // Los dos días del rango se incluyen.
        assertThat(herramientas.totalGastado(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 3),
                HerramientasConsulta.Filtros.NINGUNO).total()).isEqualByComparingTo("1500");
    }

    @Test
    void unFiltroQueNoExisteDiceLasOpciones() {
        assertThatThrownBy(() -> herramientas.totalGastado(OCT_1, OCT_31,
                new HerramientasConsulta.Filtros("Golosinas", null, null, null)))
                .isInstanceOf(HerramientasConsulta.HerramientaInvalida.class)
                .hasMessageStartingWith("No existe la categoría \"Golosinas\". Las que hay: ")
                .hasMessageContaining("Kiosco, Otros");
    }

    @Test
    void gastoPorCategoriaDeMayorAMenor() {
        HerramientasConsulta.PorCategoria p = herramientas.gastoPorCategoria(OCT_1, OCT_31);

        assertThat(p.total()).isEqualByComparingTo("24650");
        assertThat(p.categorias()).extracting(HerramientasConsulta.CategoriaTotal::categoria, c -> c.total().toPlainString(),
                HerramientasConsulta.CategoriaTotal::cantidad)
                .containsExactly(tuple("Transporte / Moto", "12000.00", 1), tuple("Supermercado", "10250.00", 2),
                        tuple("Kiosco", "2400.00", 2));
    }

    @Test
    void compararOctubreConSeptiembre() {
        java.time.LocalDateTime inicio = LocalDate.of(2026, 9, 1).atStartOfDay().minusDays(1);
        jdbc.update("INSERT INTO movimiento (fecha_hora, monto, tipo, origen, estado, creado_en, categoria_id) VALUES (?, CAST(0.01 AS NUMERIC(14, 2)), 'GASTO', 'TEXTO', 'CONFIRMADO', ?, (SELECT MIN(id) FROM categoria))", inicio, inicio);
        HerramientasConsulta.Comparacion c = herramientas.compararPeriodos(OCT_1, OCT_31, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30));

        assertThat(c.a().total()).isEqualByComparingTo("24650");
        assertThat(c.b().total()).isEqualByComparingTo("32000");
        assertThat(c.diferencia()).isEqualByComparingTo("-7350");
        assertThat(c.variacionPorcentaje()).isEqualTo(-23);
        assertThat(c.porCategoria()).extracting(HerramientasConsulta.CategoriaComparada::categoria)
                .containsExactly("Supermercado", "Transporte / Moto", "Kiosco");
    }

    @Test
    void compararContraUnMesSinDatosNoTieneDiferencia() {
        // El registro empieza el 5 de septiembre: agosto no tiene datos, y septiembre no está completo.
        HerramientasConsulta.Comparacion c = herramientas.compararPeriodos(OCT_1, OCT_31, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31));

        assertThat(c.a().conDatos()).isTrue();
        assertThat(c.b().conDatos()).isFalse();
        assertThat(c.b().total()).isEqualByComparingTo("0");
        assertThat(c.diferencia()).isNull();
        assertThat(c.variacionPorcentaje()).isNull();
        assertThat(herramientas.totalGastado(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                HerramientasConsulta.Filtros.NINGUNO).conDatos()).isFalse();
    }

    @Test
    void listarMovimientosConTope() {
        HerramientasConsulta.Listado l = herramientas.listarMovimientos(OCT_1, OCT_31,
                HerramientasConsulta.Filtros.NINGUNO, 2);

        assertThat(l.encontrados()).isEqualTo(5);
        assertThat(l.total()).isEqualByComparingTo("24650");
        assertThat(l.movimientos()).extracting(HerramientasConsulta.MovimientoListado::fecha)
                .containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 15)); // los más nuevos
        assertThat(herramientas.listarMovimientos(OCT_1, OCT_31, HerramientasConsulta.Filtros.NINGUNO, 500)
                .movimientos()).hasSize(5);
        assertThat(herramientas.listarMovimientos(OCT_1, OCT_31, new HerramientasConsulta.Filtros("Kiosco", null,
                null, null), 20).movimientos()).extracting(HerramientasConsulta.MovimientoListado::medio)
                .containsExactly("QR · Mercado Pago", "Efectivo");
    }

    @Test
    void buscarProductoEnLosTickets() {
        HerramientasConsulta.Producto p = herramientas.buscarProducto("seven up", OCT_1, OCT_31);

        assertThat(p.unidades()).isEqualByComparingTo("3");
        assertThat(p.total()).isEqualByComparingTo("6750");
        assertThat(p.compras()).isEqualTo(2);
        assertThat(p.coincidencias()).containsExactly("Seven Up lata 354 ml", "Seven Up");
        // También por la descripción impresa, y nada fuera del rango.
        assertThat(herramientas.buscarProducto("LATA", OCT_1, OCT_31).unidades()).isEqualByComparingTo("2");
        assertThat(herramientas.buscarProducto("seven", OCT_1, LocalDate.of(2026, 10, 14)).compras()).isZero();
        assertThatThrownBy(() -> herramientas.buscarProducto(" ", OCT_1, OCT_31))
                .isInstanceOf(HerramientasConsulta.HerramientaInvalida.class);
    }

    @Test
    void ingresosYDisponibleDelMes() {
        HerramientasConsulta.IngresosYDisponible i = herramientas.ingresosYDisponible(YearMonth.of(2026, 10));

        assertThat(i.ingresos()).isEqualByComparingTo("405000");
        assertThat(i.sueldo()).isEqualByComparingTo("380000");
        assertThat(i.extras()).isEqualByComparingTo("25000");
        assertThat(i.gastado()).isEqualByComparingTo("24650");
        assertThat(i.disponible()).isEqualByComparingTo("380350");
        assertThat(i.porcentajeGastado()).isEqualTo(6);
        assertThat(herramientas.ingresosYDisponible(YearMonth.of(2026, 9)).porcentajeGastado()).isNull();
    }

    // --- Datos ---

    private MovimientoInterpretado interpretado(String categoria, String descripcion, String monto, LocalDate dia,
            String medio, String cuenta) {
        MedioPago m = medio == null ? null
                : medios.findAll().stream().filter(x -> x.getNombre().equals(medio)).findFirst().orElseThrow();
        Cuenta c = cuenta == null ? null
                : cuentas.findAll().stream().filter(x -> x.getNombre().equals(cuenta)).findFirst().orElseThrow();
        return MovimientoInterpretado.conCategoria(new BigDecimal(monto), descripcion,
                categorias.findByNombre(categoria).orElseThrow(), m, c, null, dia.atTime(12, 0));
    }

    private void gasto(String categoria, String descripcion, String monto, LocalDate dia, String medio,
            String cuenta) {
        Movimiento m = movimientos.crearPendiente(interpretado(categoria, descripcion, monto, dia, medio, cuenta),
                Origen.TEXTO, descripcion);
        movimientos.confirmar(m.getId());
    }

    private void ticket(String comercio, String monto, LocalDate dia, List<ItemTicket> items) {
        Movimiento m = movimientos.crearPendienteDeTicket(
                interpretado("Supermercado", comercio, monto, dia, "Débito", null), DatosTicket.de(comercio, items),
                null);
        movimientos.confirmar(m.getId());
    }
}
