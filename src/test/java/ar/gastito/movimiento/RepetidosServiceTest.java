package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.catalogo.TarjetaCreditoRepository;

/** Hoy es el martes 20/10/2026 a las 15:00. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RepetidosService.class, MovimientoService.class, RepetidosServiceTest.Config.class})
class RepetidosServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 20, 15, 0);

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }

        @Bean
        RepetidosProperties repetidosProperties() {
            return new RepetidosProperties(Duration.ofHours(3));
        }
    }

    @Autowired
    RepetidosService repetidos;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    MovimientoRepository repositorio;

    @Autowired
    CategoriaRepository categorias;

    @Autowired
    MedioPagoRepository medios;

    @Autowired
    TarjetaCreditoRepository tarjetas;

    @Autowired
    JdbcTemplate jdbc;


    private Categoria categoria(String nombre) {
        return categorias.findByNombre(nombre).orElseThrow();
    }

    private MedioPago medio(String nombre) {
        return medios.findAll().stream().filter(m -> m.getNombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Movimiento confirmado(String monto, String descripcion, String categoria, LocalDateTime fecha) {
        Movimiento m = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto),
                descripcion, categoria(categoria), medio("Efectivo"), null, null, fecha), Origen.AUDIO, descripcion);
        movimientos.confirmar(m.getId());
        return m;
    }

    private Movimiento nuevoTexto(String monto, String categoria, LocalDateTime fecha) {
        return movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Algo",
                categoria(categoria), null, null, null, fecha), Origen.TEXTO, "algo " + monto);
    }

    private Movimiento nuevoTicket(String monto, LocalDateTime fecha) {
        TarjetaCredito tarjeta = tarjetas.findAll().get(0);
        MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(new BigDecimal(monto), "Día",
                categoria("Supermercado"), medio("Tarjeta de crédito"), null, tarjeta, fecha);
        return movimientos.crearPendienteDeTicket(interpretado, new DatosTicket("Día", "5601",
                new BigDecimal(monto), null, List.of(), List.of(new ItemTicket("Yerba", BigDecimal.ONE,
                        new BigDecimal(monto), new BigDecimal(monto)))), null);
    }

    // --- Buscar ---

    @Test
    void unTicketQueYaSeHabiaDichoPorAudioSeEncuentra() {
        Movimiento audio = confirmado("8050", "Súper en el Día", "Supermercado", AHORA.minusHours(2));

        Movimiento ticket = nuevoTicket("8050", AHORA.minusMinutes(80));

        assertThat(repetidos.buscar(ticket)).get().extracting(Movimiento::getId).isEqualTo(audio.getId());
    }

    @Test
    void fueraDeLaVentanaOConOtroMontoNoHayRepetido() {
        confirmado("8050", "Súper", "Supermercado", AHORA.minusHours(5));
        confirmado("9100", "Súper", "Supermercado", AHORA.minusMinutes(30));

        assertThat(repetidos.buscar(nuevoTexto("8050", "Supermercado", AHORA))).isEmpty();
    }

    @Test
    void conOtraCategoriaNoEsElMismo() {
        confirmado("1500", "Alfajor", "Kiosco", AHORA.minusMinutes(10));

        assertThat(repetidos.buscar(nuevoTexto("1500", "Salidas", AHORA))).isEmpty();
    }

    @Test
    void lasCuotasFuturasNoParticipan() {
        // Una cuota de la semana que viene... dentro de la ventana del gasto nuevo, que se dice para el futuro.
        Movimiento futuro = confirmado("14000", "Zapatillas (cuota 2 de 6)", "Ropa", AHORA.plusHours(2));

        assertThat(repetidos.buscar(nuevoTexto("14000", "Ropa", AHORA.plusHours(1)))).isEmpty();
        assertThat(futuro.getFechaHora()).isAfter(AHORA);
    }

    @Test
    void unaJuntadaSeComparaPorElTotalPagadoNoPorMiParte() {
        Movimiento asado = confirmado("8000", "Asado", "Salidas", AHORA.minusHours(1));
        jdbc.update("INSERT INTO juntada (movimiento_id, total_pagado, personas, creado_en) VALUES (?, 48000, 6, ?)",
                asado.getId(), AHORA);

        assertThat(repetidos.buscar(nuevoTexto("48000", "Salidas", AHORA))).get()
                .extracting(Movimiento::getId).isEqualTo(asado.getId());
        assertThat(repetidos.buscar(nuevoTexto("8000", "Salidas", AHORA))).isEmpty();
    }

    @Test
    void elCobroPendienteDeUnFijoDelMesSeEncuentraAunqueSeaDeOtroDia() {
        long fijo = cobroDeFijoPendiente("32900", LocalDate.of(2026, 10, 4));

        assertThat(repetidos.buscar(nuevoTexto("32900", "Seguros", AHORA))).get()
                .extracting(Movimiento::getId).isEqualTo(fijo);
        // Un pendiente cualquiera (otra tarjeta sin guardar) no.
        nuevoTexto("5000", "Kiosco", AHORA.minusMinutes(5));
        assertThat(repetidos.buscar(nuevoTexto("5000", "Kiosco", AHORA))).isEmpty();
    }

    @Test
    void loGeneradoPorGastitoNoSeCompara() {
        confirmado("1500", "Alfajor", "Kiosco", AHORA.minusMinutes(10));
        Movimiento automatico = movimientos.crearPendiente(MovimientoInterpretado.conCategoria(new BigDecimal("1500"),
                "x", categoria("Kiosco"), null, null, null, AHORA), Origen.AUTOMATICO, null);

        assertThat(repetidos.buscar(automatico)).isEmpty();
    }

    /**
     * Como en el bot: sin una transacción alrededor, la tarjeta se arma con lo que devolvió el servicio. Si los
     * productos no vinieran cargados, contarlos fallaría (LazyInitializationException).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void loEncontradoYLoUnidoLleganConLosProductosCargados() {
        Movimiento ticket = nuevoTicket("8050", AHORA.minusHours(2));
        Movimiento texto = null;
        try {
            movimientos.confirmar(ticket.getId());
            texto = nuevoTexto("8050", "Supermercado", AHORA.minusHours(1));

            Movimiento encontrado = repetidos.buscar(texto).orElseThrow();
            assertThat(encontrado.getItems()).hasSize(1);

            Movimiento unido = repetidos.unir(texto.getId(), ticket.getId()).movimiento();
            assertThat(unido.getItems()).hasSize(1);
        } finally {
            // Sin la transacción del test no hay rollback: se borra a mano (los productos y la unión van en cascada).
            jdbc.update("DELETE FROM movimiento WHERE id IN (?, ?)", ticket.getId(),
                    texto == null ? ticket.getId() : texto.getId());
        }
    }

    // --- Unir ---

    @Test
    void alUnirQuedaLoMejorDeCadaUno() {
        Movimiento audio = confirmado("8000", "Súper en el Día", "Supermercado", AHORA.minusHours(2));
        Movimiento ticket = nuevoTicket("8050", AHORA.minusMinutes(80));
        jdbc.update("""
                INSERT INTO uso_ia (fecha_hora, modelo, tokens_entrada, tokens_salida, proposito, costo_usd, movimiento_id)
                VALUES (?, 'claude-haiku-5-5', 100, 10, 'TICKET', 0.0001, ?)""", AHORA, ticket.getId());

        RepetidosService.Union union = repetidos.unir(ticket.getId(), audio.getId());

        Movimiento queda = repositorio.findConDetalleById(audio.getId()).orElseThrow();
        assertThat(union.movimiento().getId()).isEqualTo(audio.getId());
        assertThat(queda.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(queda.getDescripcion()).isEqualTo("Súper en el Día"); // la del audio
        assertThat(queda.getMonto()).isEqualByComparingTo("8050"); // el total impreso
        assertThat(queda.getFechaHora()).isEqualTo(AHORA.minusMinutes(80)); // la hora del ticket
        assertThat(queda.getTarjeta()).isNotNull(); // el medio más completo
        assertThat(queda.getOrigen()).isEqualTo(Origen.TICKET);
        assertThat(queda.getComercio()).isEqualTo("Día");
        assertThat(queda.getSucursal()).isEqualTo("5601");
        assertThat(queda.getItems()).extracting(MovimientoItem::getDescripcion).containsExactly("Yerba");
        assertThat(repositorio.findById(ticket.getId())).isEmpty();
        assertThat(repetidos.uniones(audio.getId())).singleElement().satisfies(u -> {
            assertThat(u.getOrigen()).isEqualTo(Origen.TICKET);
            assertThat(u.getMonto()).isEqualByComparingTo("8050");
        });
        assertThat(jdbc.queryForObject("SELECT movimiento_id FROM uso_ia", Long.class)).isEqualTo(audio.getId());
    }

    @Test
    void unirConElCobroDeUnFijoLoConfirmaConLoCargado() {
        long fijo = cobroDeFijoPendiente("32900", LocalDate.of(2026, 10, 4));
        Movimiento cargado = nuevoTexto("33100", "Seguros", AHORA);

        repetidos.unir(cargado.getId(), fijo);

        Movimiento queda = repositorio.findById(fijo).orElseThrow();
        assertThat(queda.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(queda.getMonto()).isEqualByComparingTo("33100");
        assertThat(queda.getFechaHora()).isEqualTo(AHORA);
        assertThat(queda.getDescripcion()).isEqualTo("Algo");
        assertThat(queda.getGastoFijoId()).isNotNull();
        assertThat(queda.getOrigen()).isEqualTo(Origen.TEXTO);
    }

    @Test
    void alUnirConUnaJuntadaQuedaMiParteAunqueElOtroTraigaElTotal() {
        Movimiento asado = nuevoTexto("48000", "Salidas", AHORA.minusHours(1));
        asado.prepararJuntada(new BigDecimal("8000"));
        movimientos.confirmar(asado.getId());
        jdbc.update("INSERT INTO juntada (movimiento_id, total_pagado, personas, creado_en) VALUES (?, 48000, 6, ?)",
                asado.getId(), AHORA);
        Movimiento aviso = nuevoTexto("48000", "Salidas", AHORA);

        repetidos.unir(aviso.getId(), asado.getId());

        Movimiento queda = repositorio.findById(asado.getId()).orElseThrow();
        assertThat(queda.getMonto()).isEqualByComparingTo("8000");
        assertThat(queda.isJuntada()).isTrue();
        assertThat(repositorio.totalDeJuntada(asado.getId())).isEqualByComparingTo("48000");
    }

    @Test
    void siLaJuntadaEsLaNuevaPasaAlQueQueda() {
        Movimiento ticket = nuevoTicket("48000", AHORA.minusHours(1));
        movimientos.confirmar(ticket.getId());
        Movimiento asado = nuevoTexto("48000", "Supermercado", AHORA);
        asado.prepararJuntada(new BigDecimal("8000"));
        repositorio.flush();
        jdbc.update("INSERT INTO juntada (movimiento_id, total_pagado, personas, creado_en) VALUES (?, 48000, 6, ?)",
                asado.getId(), AHORA);

        repetidos.unir(asado.getId(), ticket.getId());

        Movimiento queda = repositorio.findConDetalleById(ticket.getId()).orElseThrow();
        assertThat(queda.getMonto()).isEqualByComparingTo("8000"); // mi parte, no el total del ticket
        assertThat(queda.isJuntada()).isTrue();
        assertThat(queda.getItems()).hasSize(1);
        assertThat(repositorio.totalDeJuntada(ticket.getId())).isEqualByComparingTo("48000");
        assertThat(repositorio.findById(asado.getId())).isEmpty();
    }

    /** Un gasto fijo y su cobro de octubre, pendiente, como lo dejaría Gastito el día del cobro. */
    private long cobroDeFijoPendiente(String monto, LocalDate dia) {
        long categoria = categoria("Seguros").getId();
        jdbc.update("""
                INSERT INTO gasto_fijo (nombre, monto_esperado, categoria_id, dia_del_mes, frecuencia, mes_inicio, creado_en)
                VALUES ('Seguro', ?, ?, ?, 'MENSUAL', ?, ?)""", new BigDecimal(monto), categoria, dia.getDayOfMonth(),
                dia.withDayOfMonth(1), AHORA);
        Long fijoId = jdbc.queryForObject("SELECT MAX(id) FROM gasto_fijo", Long.class);
        jdbc.update("""
                INSERT INTO movimiento (fecha_hora, monto, descripcion, tipo, categoria_id, origen, estado, creado_en,
                                        gasto_fijo_id, fijo_periodo)
                VALUES (?, ?, 'Seguro', 'GASTO', ?, 'AUTOMATICO', 'PENDIENTE', ?, ?, ?)""", dia.atTime(9, 0),
                new BigDecimal(monto), categoria, AHORA, fijoId, dia.withDayOfMonth(1));
        return jdbc.queryForObject("SELECT MAX(id) FROM movimiento", Long.class);
    }
}
