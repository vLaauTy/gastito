package ar.gastito.movimiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;

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

    @Autowired
    MedioPagoRepository medios;

    @Autowired
    TestEntityManager entityManager;

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

    @Test
    void unGastoDudosoGuardaSusCandidatasEnOrdenYNoSePuedeGuardarSinCategoria() {
        Movimiento m = service.crearPendiente(new MovimientoInterpretado(new BigDecimal("3700"), "Pan de pancho",
                null, List.of(categoria("Supermercado"), categoria("Kiosco")), null, null, null, AHORA),
                Origen.TEXTO, "test");

        Movimiento leido = service.buscar(m.getId());
        assertThat(leido.getCategoria()).isNull();
        assertThat(leido.getCandidatas()).extracting(Categoria::getNombre).containsExactly("Supermercado", "Kiosco");
        assertThat(leido.isCompleto()).isFalse();
        assertThatThrownBy(() -> service.confirmar(m.getId())).isInstanceOf(MovimientoIncompletoException.class);

        Movimiento conCategoria = service.elegirCategoria(m.getId(), categoria("Supermercado").getId());
        assertThat(conCategoria.isCompleto()).isTrue();
        assertThat(service.confirmar(m.getId()).totalMesCategoria()).isEqualByComparingTo("3700");
    }

    @Test
    void unGastoSinMontoSeCompletaEditando() {
        Movimiento m = service.crearPendiente(MovimientoInterpretado.conCategoria(null, "Pan", categoria("Kiosco"),
                null, null, null, AHORA), Origen.TEXTO, "test");
        assertThatThrownBy(() -> service.confirmar(m.getId())).isInstanceOf(MovimientoIncompletoException.class);

        service.cambiarMonto(m.getId(), new BigDecimal("3700"));

        assertThat(service.confirmar(m.getId()).movimiento().getMonto()).isEqualByComparingTo("3700");
    }

    @Test
    void editarCampos() {
        Movimiento m = pendiente(categoria("Kiosco"), "1500", AHORA);

        service.cambiarDescripcion(m.getId(), "Alfajores");
        service.cambiarFechaHora(m.getId(), AHORA.minusDays(1));
        service.elegirCategoria(m.getId(), categoria("Supermercado").getId());
        Movimiento editado = service.cambiarMonto(m.getId(), new BigDecimal("1800"));

        assertThat(editado.getDescripcion()).isEqualTo("Alfajores");
        assertThat(editado.getFechaHora()).isEqualTo(AHORA.minusDays(1));
        assertThat(editado.getCategoria().getNombre()).isEqualTo("Supermercado");
        assertThat(editado.getMonto()).isEqualByComparingTo("1800");
        assertThatThrownBy(() -> service.cambiarMonto(m.getId(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cambiarAMedioTarjetaAsociaLaTarjetaYSacaLaCuenta() {
        Movimiento m = pendiente(categoria("Kiosco"), "1500", AHORA);
        Long credito = medios.findByActivoTrueOrderById().stream()
                .filter(MedioPago::esTarjetaDeCredito).findFirst().orElseThrow().getId();
        Long efectivo = medios.findByActivoTrueOrderById().get(0).getId();

        Movimiento conTarjeta = service.cambiarMedioPago(m.getId(), credito);
        assertThat(conTarjeta.getTarjeta().getNombre()).isEqualTo("MasterCard Gold");

        Movimiento conEfectivo = service.cambiarMedioPago(m.getId(), efectivo);
        assertThat(conEfectivo.getMedioPago().getNombre()).isEqualTo("Efectivo");
        assertThat(conEfectivo.getTarjeta()).isNull();

        assertThat(service.cambiarMedioPago(m.getId(), null).getMedioPago()).isNull();
    }

    @Test
    void noSeEditaUnGastoYaGuardado() {
        Movimiento m = confirmado(categoria("Kiosco"), "1500", AHORA);
        assertThatThrownBy(() -> service.cambiarMonto(m.getId(), BigDecimal.TEN))
                .isInstanceOf(MovimientoNoPendienteException.class);
    }

    @Test
    void ultimasConfirmacionesDeLaMasNueva() {
        confirmado(categoria("Kiosco"), "1", AHORA);
        Movimiento ultimo = confirmado(categoria("Ropa"), "2", AHORA);
        pendiente(categoria("Salidas"), "3", AHORA);

        assertThat(service.ultimasConfirmaciones()).extracting(Movimiento::getId)
                .startsWith(ultimo.getId()).hasSize(2);
    }

    private Categoria categoria(String nombre) {
        return categorias.findByNombre(nombre).orElseThrow();
    }

    @Test
    void unTextoOriginalMasLargoQueLaColumnaSeRecortaYSeGuarda() {
        MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(new BigDecimal("100"), "test",
                categoria("Kiosco"), null, null, null, AHORA);

        Movimiento m = service.crearPendiente(interpretado, Origen.AUDIO, "a".repeat(1500));

        assertThat(service.buscar(m.getId()).getTextoOriginal()).hasSize(1000);
    }

    @Test
    void unTicketGuardaSusProductosEnOrdenConElDescuentoNegativo() {
        MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(new BigDecimal("8050.00"),
                "Supermercado Día", categoria("Supermercado"), null, null, null, AHORA);
        List<ItemTicket> items = List.of(
                new ItemTicket("Leche 1 L", "LECHE ENT 1L", "7790080001234", new BigDecimal("2"),
                        new BigDecimal("1450.00"), new BigDecimal("2900.00")),
                new ItemTicket("Yerba 500 g", null, null, new BigDecimal("3900.00")),
                new ItemTicket("Galletitas", null, null, new BigDecimal("1500.00")),
                new ItemTicket("Descuento 2x1", null, null, new BigDecimal("-250.00")));
        DatosTicket ticket = new DatosTicket("DIA TIENDA 5601", "5601", new BigDecimal("8300.00"),
                new BigDecimal("-250.00"), List.of("Renglón 3: el importe está tapado", "Renglón 5: cortado"), items);

        Movimiento m = service.crearPendienteDeTicket(interpretado, ticket, "con débito");
        service.asociarFotoTicket(m.getId(), "tickets/2026/10/" + m.getId() + ".jpg");
        entityManager.flush();
        entityManager.clear();

        Movimiento leido = service.buscar(m.getId());
        assertThat(leido.getOrigen()).isEqualTo(Origen.TICKET);
        assertThat(leido.getComercio()).isEqualTo("DIA TIENDA 5601");
        assertThat(leido.getFotoTicket()).isEqualTo("tickets/2026/10/" + m.getId() + ".jpg");
        assertThat(leido.getTextoOriginal()).isEqualTo("con débito");
        assertThat(leido.getItems()).extracting(MovimientoItem::getDescripcion)
                .containsExactly("Leche 1 L", "Yerba 500 g", "Galletitas", "Descuento 2x1");
        assertThat(leido.getItems().get(0).getCantidad()).isEqualByComparingTo("2");
        assertThat(leido.getItems().get(3).isDescuento()).isTrue();
        assertThat(leido.getItems().get(3).getSubtotal()).isEqualByComparingTo("-250");
        assertThat(leido.getItems().get(0).getDescripcionOriginal()).isEqualTo("LECHE ENT 1L");
        assertThat(leido.getItems().get(0).getCodigoBarras()).isEqualTo("7790080001234");
        assertThat(leido.getItems().get(1).getCodigoBarras()).isNull();
        assertThat(leido.getSucursal()).isEqualTo("5601");
        assertThat(leido.getSubtotalImpreso()).isEqualByComparingTo("8300");
        assertThat(leido.getDescuentosImpresos()).isEqualByComparingTo("-250");
        assertThat(leido.getLineasIlegibles())
                .containsExactly("Renglón 3: el importe está tapado", "Renglón 5: cortado");
    }

    @Test
    void unTicketConCategoriasCandidatasNoRepiteLosProductos() {
        // Con la foto real: tres candidatas y 22 renglones daban 66 (el JOIN multiplicaba candidatas × productos).
        MovimientoInterpretado dudoso = new MovimientoInterpretado(new BigDecimal("4400"), "Día", null,
                List.of(categoria("Supermercado"), categoria("Kiosco"), categoria("Productos para la casa")), null, null,
                null, AHORA);
        List<ItemTicket> items = List.of(
                new ItemTicket("Galletitas", null, null, new BigDecimal("4500")),
                new ItemTicket("20% ClubDía", null, null, new BigDecimal("-900")),
                new ItemTicket("Bolsa", null, null, new BigDecimal("800")));

        Movimiento m = service.crearPendienteDeTicket(dudoso, DatosTicket.de("Día", items), null);
        entityManager.flush();
        entityManager.clear();

        Movimiento leido = service.buscar(m.getId());
        assertThat(leido.getCandidatas()).hasSize(3);
        assertThat(leido.getItems()).extracting(MovimientoItem::getDescripcion)
                .containsExactly("Galletitas", "20% ClubDía", "Bolsa");
    }

    @Test
    void unTicketSinDatosExtraNoTieneLineasIlegibles() {
        MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(new BigDecimal("1500"),
                "Kiosco", categoria("Kiosco"), null, null, null, AHORA);

        Movimiento m = service.crearPendienteDeTicket(interpretado, DatosTicket.de("Kiosco", List.of()), null);
        entityManager.flush();
        entityManager.clear();

        Movimiento leido = service.buscar(m.getId());
        assertThat(leido.getLineasIlegibles()).isEmpty();
        assertThat(leido.getSucursal()).isNull();
        assertThat(leido.getSubtotalImpreso()).isNull();
    }

    @Test
    void soloUnTicketPuedeTenerFoto() {
        Movimiento texto = pendiente(categoria("Kiosco"), "1500", AHORA);

        assertThatThrownBy(() -> service.asociarFotoTicket(texto.getId(), "tickets/x.jpg"))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Editar y borrar lo ya guardado ---

    @Test
    void editarCambiaTodoEnUnGastoGuardado() {
        Movimiento m = confirmado(categoria("Kiosco"), "1500", AHORA);
        MedioPago credito = medios.findAll().stream().filter(MedioPago::esTarjetaDeCredito).findFirst().orElseThrow();
        Long galicia = entityManager.getEntityManager()
                .createQuery("select c.id from Cuenta c where c.nombre = 'Galicia'", Long.class).getSingleResult();

        Movimiento editado = service.editar(m.getId(), new BigDecimal("1800"), "  Alfajor  ", AHORA.minusDays(1),
                categoria("Salidas").getId(), credito.getId(), galicia);

        assertThat(editado.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(editado.getMonto()).isEqualByComparingTo("1800");
        assertThat(editado.getDescripcion()).isEqualTo("Alfajor");
        assertThat(editado.getCategoria().getNombre()).isEqualTo("Salidas");
        // Con crédito, la cuenta queda vacía: es la que paga el resumen.
        assertThat(editado.getTarjeta()).isNotNull();
        assertThat(editado.getCuenta()).isNull();

        Movimiento sinMedio = service.editar(m.getId(), new BigDecimal("1800"), null, AHORA,
                categoria("Salidas").getId(), null, galicia);
        assertThat(sinMedio.getMedioPago()).isNull();
        assertThat(sinMedio.getTarjeta()).isNull();
        assertThat(sinMedio.getCuenta().getNombre()).isEqualTo("Galicia");
    }

    @Test
    void editarNoAceptaDatosInvalidos() {
        Long id = confirmado(categoria("Kiosco"), "1500", AHORA).getId();
        Long kiosco = categoria("Kiosco").getId();

        assertThatThrownBy(() -> service.editar(id, BigDecimal.ZERO, null, AHORA, kiosco, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.editar(id, BigDecimal.TEN, null, AHORA, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.editar(id, BigDecimal.TEN, null, AHORA, 9999L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elMontoDeUnaJuntadaNoSeEditaSuelto() {
        Movimiento m = pendiente(categoria("Salidas"), "30000", AHORA);
        m.prepararJuntada(new BigDecimal("5000"));
        service.confirmar(m.getId());
        Long salidas = categoria("Salidas").getId();

        assertThatThrownBy(() -> service.editar(m.getId(), new BigDecimal("6000"), "test", AHORA, salidas, null,
                null)).isInstanceOf(MovimientoEspecialException.class);
        // Lo demás sí.
        assertThat(service.editar(m.getId(), new BigDecimal("5000"), "Asado", AHORA, salidas, null, null)
                .getDescripcion()).isEqualTo("Asado");
    }

    @Test
    void borrarSacaElGastoConSusProductosYDejaElUsoDeLaIa() {
        MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(new BigDecimal("8050"), "Día",
                categoria("Supermercado"), null, null, null, AHORA);
        Movimiento m = service.crearPendienteDeTicket(interpretado, DatosTicket.de("Día", List.of(
                new ItemTicket("Leche", null, null, new BigDecimal("8050")))), null);
        service.confirmar(m.getId());
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO uso_ia (fecha_hora, modelo, tokens_entrada, tokens_salida, proposito, costo_usd, movimiento_id)
                VALUES (CURRENT_TIMESTAMP, 'claude-haiku-5-5', 10, 5, 'TICKET', 0.000001, :id)""")
                .setParameter("id", m.getId()).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        service.borrar(m.getId());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> service.buscar(m.getId())).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM movimiento_item WHERE movimiento_id = " + m.getId())
                .getSingleResult()).isEqualTo(0L);
        assertThat(entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM uso_ia WHERE movimiento_id IS NULL").getSingleResult())
                .isEqualTo(1L);
    }

    @Test
    void unaJuntadaNoSeBorraComoUnGastoComun() {
        Movimiento juntada = pendiente(categoria("Salidas"), "30000", AHORA);
        juntada.prepararJuntada(new BigDecimal("5000"));

        assertThatThrownBy(() -> service.borrar(juntada.getId())).isInstanceOf(MovimientoEspecialException.class);
    }

    private Movimiento pendiente(Categoria categoria, String monto, LocalDateTime fecha) {
        MovimientoInterpretado interpretado = MovimientoInterpretado.conCategoria(new BigDecimal(monto), "test",
                categoria, null, null, null, fecha);
        return service.crearPendiente(interpretado, Origen.TEXTO, "test");
    }

    private Movimiento confirmado(Categoria categoria, String monto, LocalDateTime fecha) {
        Movimiento m = pendiente(categoria, monto, fecha);
        service.confirmar(m.getId());
        return m;
    }
}
