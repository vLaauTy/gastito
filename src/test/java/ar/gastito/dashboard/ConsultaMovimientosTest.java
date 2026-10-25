package ar.gastito.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;

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
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.catalogo.TarjetaCreditoRepository;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.ticket.FotosTicket;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ConsultaMovimientos.class, MovimientoService.class, IngresoService.class, ConsultaMovimientosTest.Reloj.class})
class ConsultaMovimientosTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 21, 16, 42);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        }

        @Bean
        FotosTicket fotosTicket() throws IOException {
            return new FotosTicket(Files.createTempDirectory("gastito-fotos"));
        }
    }

    @Autowired
    ConsultaMovimientos consulta;

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

    @Autowired
    TarjetaCreditoRepository tarjetas;

    private Categoria categoria(String nombre) {
        return categorias.findByNombre(nombre).orElseThrow();
    }

    private MedioPago medio(String nombre) {
        return medios.findAll().stream().filter(m -> m.getNombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Cuenta cuenta(String nombre) {
        return cuentas.findAll().stream().filter(c -> c.getNombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Movimiento gasto(String descripcion, String monto, int dia, String categoria, MedioPago medio,
            Cuenta cuenta, Origen origen) {
        Movimiento m = movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal(monto), descripcion,
                categoria(categoria), List.of(), medio, cuenta, null, LocalDateTime.of(2026, 10, dia, 10, 0)),
                origen, descripcion);
        movimientos.confirmar(m.getId());
        return m;
    }

    private Movimiento gasto(String descripcion, String monto, int dia) {
        return gasto(descripcion, monto, dia, "Kiosco", medio("Efectivo"), null, Origen.TEXTO);
    }

    private void septiembre(String descripcion, String monto) {
        Movimiento m = movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal(monto), descripcion,
                categoria("Kiosco"), List.of(), null, null, null, LocalDateTime.of(2026, 9, 30, 23, 59)),
                Origen.TEXTO, descripcion);
        movimientos.confirmar(m.getId());
    }

    private static ConsultaMovimientos.Filtros filtros() {
        return new ConsultaMovimientos.Filtros(OCTUBRE, null, null, null, null);
    }

    private List<String> descripciones(ConsultaMovimientos.Filtros f) {
        return consulta.buscar(f, 0, 50).elementos().stream().map(ConsultaMovimientos.Fila::descripcion).toList();
    }

    @Test
    void masNuevosPrimeroYSoloGastosConfirmadosDelMes() {
        gasto("Primero", "100", 1);
        gasto("Segundo", "200", 2);
        septiembre("De septiembre", "300");
        movimientos.crearPendiente(new MovimientoInterpretado(new BigDecimal("5"), "Pendiente", categoria("Kiosco"),
                List.of(), null, null, null, LocalDateTime.of(2026, 10, 3, 10, 0)), Origen.TEXTO, "x");
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), "Sueldo", null, LocalDate.of(2026, 10, 1));

        assertThat(descripciones(filtros())).containsExactly("Segundo", "Primero");
    }

    @Test
    void pagina() {
        for (int d = 1; d <= 5; d++) {
            gasto("Gasto " + d, "100", d);
        }

        ConsultaMovimientos.Pagina p = consulta.buscar(filtros(), 1, 2);

        assertThat(p.elementos()).extracting(ConsultaMovimientos.Fila::descripcion)
                .containsExactly("Gasto 3", "Gasto 2");
        assertThat(p.totalElementos()).isEqualTo(5);
        assertThat(p.totalPaginas()).isEqualTo(3);
        assertThatThrownBy(() -> consulta.buscar(filtros(), 0, 500)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtraPorCategoriaMedioYOrigen() {
        gasto("Pan", "100", 1, "Supermercado", medio("Efectivo"), null, Origen.TEXTO);
        gasto("Alfajor", "100", 2, "Kiosco", medio("QR"), cuenta("Mercado Pago"), Origen.AUDIO);
        gasto("Chicle", "100", 3, "Kiosco", medio("Efectivo"), null, Origen.TEXTO);

        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, categoria("Kiosco").getId(), null, null,
                null))).containsExactly("Chicle", "Alfajor");
        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, medio("QR").getId(), null, null)))
                .containsExactly("Alfajor");
        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, null, Origen.AUDIO, null)))
                .containsExactly("Alfajor");
    }

    @Test
    void buscaSinDistinguirTildesNiMayusculas() {
        gasto("Café con medialunas", "100", 1);
        gasto("CAFÉ en grano", "100", 2);
        gasto("Cafetería", "100", 3);
        gasto("Pan", "100", 4);
        gasto("Piña colada", "100", 5);

        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, null, null, "cafe")))
                .containsExactly("Cafetería", "CAFÉ en grano", "Café con medialunas");
        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, null, null, "  MEDIALUNAS ")))
                .containsExactly("Café con medialunas");
        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, null, null, "pina")))
                .containsExactly("Piña colada");
    }

    @Test
    void losComodinesDeLaBusquedaSeTomanComoTexto() {
        gasto("Descuento 50% off", "100", 1);
        gasto("Otra cosa", "100", 2);

        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, null, null, "%")))
                .containsExactly("Descuento 50% off");
        assertThat(descripciones(new ConsultaMovimientos.Filtros(OCTUBRE, null, null, null, "_"))).isEmpty();
    }

    @Test
    void laColumnaMedioYSinEspecificar() {
        gasto("QR", "100", 1, "Kiosco", medio("QR"), cuenta("Mercado Pago"), Origen.TEXTO);
        gasto("Efectivo", "100", 2, "Kiosco", medio("Efectivo"), null, Origen.TEXTO);
        gasto("Nada", "100", 3, "Kiosco", null, null, Origen.TEXTO);

        assertThat(consulta.buscar(filtros(), 0, 10).elementos())
                .extracting(ConsultaMovimientos.Fila::medio, ConsultaMovimientos.Fila::sinEspecificar)
                .containsExactly(
                        tuple("Sin especificar", true),
                        tuple("Efectivo", false),
                        tuple("QR · Mercado Pago", false));
    }

    // --- CSV ---

    @Test
    void csvParaExcelEnCastellano() {
        gasto("Pan; papas", "3700.50", 5, "Supermercado", medio("Efectivo"), null, Origen.AUDIO);
        gasto("=HYPERLINK(\"x\")", "100", 6);
        ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal("380000"), "Sueldo", cuenta("Galicia").getId(),
                LocalDate.of(2026, 10, 1));

        byte[] csv = consulta.csv(OCTUBRE);
        String texto = new String(csv, StandardCharsets.UTF_8);

        assertThat(csv[0]).isEqualTo((byte) 0xEF); // BOM de UTF-8
        assertThat(texto.lines().toList()).containsExactly(
                "﻿Fecha;Hora;Tipo;Clase;Descripción;Categoría;Medio;Origen;Monto",
                "01/10/2026;00:00;Ingreso;Sueldo;Sueldo;;Galicia;DASHBOARD;380000,00",
                "05/10/2026;10:00;Gasto;;\"Pan; papas\";Supermercado;Efectivo;AUDIO;3700,50",
                "06/10/2026;10:00;Gasto;;\"'=HYPERLINK(\"\"x\"\")\";Kiosco;Efectivo;TEXTO;100,00");
        assertThat(texto).contains("\r\n");
    }

    @Test
    void celdasPeligrosasParaExcel() {
        assertThat(ConsultaMovimientos.celda("=1+1")).isEqualTo("'=1+1");
        assertThat(ConsultaMovimientos.celda("+54 9 11")).isEqualTo("'+54 9 11");
        assertThat(ConsultaMovimientos.celda("-5")).isEqualTo("'-5");
        assertThat(ConsultaMovimientos.celda("@sum")).isEqualTo("'@sum");
        assertThat(ConsultaMovimientos.celda("normal")).isEqualTo("normal");
        assertThat(ConsultaMovimientos.celda("")).isEmpty();
    }

    // --- Tickets ---

    @Autowired
    FotosTicket fotos;

    private static final List<ItemTicket> PRODUCTOS = List.of(
            new ItemTicket("Leche 1 L", new BigDecimal("2"), new BigDecimal("1450"), new BigDecimal("2900")),
            new ItemTicket("Yerba 500 g", null, null, new BigDecimal("3900")),
            new ItemTicket("2x1 Galletitas", null, null, new BigDecimal("-250")));

    private Movimiento ticket(String total, List<ItemTicket> items, boolean confirmar) {
        Movimiento m = movimientos.crearPendienteDeTicket(new MovimientoInterpretado(new BigDecimal(total),
                "Supermercado Día", categoria("Supermercado"), List.of(), medio("Débito"), null, null,
                LocalDateTime.of(2026, 10, 18, 17, 20)), DatosTicket.de("Supermercado Día", items), null);
        if (confirmar) {
            movimientos.confirmar(m.getId());
        }
        return m;
    }

    @Test
    void laFilaDiceSiEsUnTicketYSiTieneFoto() throws IOException {
        Movimiento conFoto = ticket("6550", PRODUCTOS, true);
        String ruta = fotos.guardar(conFoto.getId(), LocalDate.of(2026, 10, 18), new byte[] {1, 2, 3});
        movimientos.asociarFotoTicket(conFoto.getId(), ruta);
        gasto("Kiosco", "1500", 20);

        List<ConsultaMovimientos.Fila> filas = consulta.buscar(filtros(), 0, 50).elementos();

        assertThat(filas).extracting(ConsultaMovimientos.Fila::descripcion, ConsultaMovimientos.Fila::ticket,
                ConsultaMovimientos.Fila::foto, ConsultaMovimientos.Fila::origen)
                .containsExactly(tuple("Kiosco", false, false, "TEXTO"),
                        tuple("Supermercado Día", true, true, "TICKET"));
    }

    @Test
    void losProductosDeUnTicketGuardadoConLaSumaYElTotal() {
        Movimiento m = ticket("6550", PRODUCTOS, true);

        ConsultaMovimientos.DetalleTicket detalle = consulta.items(m.getId());

        assertThat(detalle.comercio()).isEqualTo("Supermercado Día");
        assertThat(detalle.items()).extracting(ConsultaMovimientos.ItemDelTicket::descripcion)
                .containsExactly("Leche 1 L", "Yerba 500 g", "2x1 Galletitas");
        assertThat(detalle.items().get(2).descuento()).isTrue();
        assertThat(detalle.items().get(0).subtotal()).isEqualByComparingTo("2900");
        assertThat(detalle.items().get(0).subtotal().scale()).isEqualTo(2);
        assertThat(detalle.sumaItems()).isEqualByComparingTo("6550");
        assertThat(detalle.total()).isEqualByComparingTo("6550");
        assertThat(detalle.coincide()).isTrue();
        assertThat(detalle.foto()).isFalse();
        assertThat(consulta.foto(m.getId())).isEmpty();
    }

    @Test
    void siLosProductosNoSumanElTotalLoDice() {
        assertThat(consulta.items(ticket("9000", PRODUCTOS, true).getId()).coincide()).isFalse();
        ConsultaMovimientos.DetalleTicket sinProductos = consulta.items(ticket("9000", List.of(), true).getId());
        assertThat(sinProductos.items()).isEmpty();
        assertThat(sinProductos.sumaItems()).isNull();
        assertThat(sinProductos.coincide()).isTrue();
    }

    @Test
    void laFotoDeUnTicketGuardado() throws IOException {
        Movimiento m = ticket("6550", PRODUCTOS, true);
        movimientos.asociarFotoTicket(m.getId(),
                fotos.guardar(m.getId(), LocalDate.of(2026, 10, 18), new byte[] {(byte) 0xFF, (byte) 0xD8}));

        assertThat(consulta.foto(m.getId())).isPresent();
        assertThat(Files.readAllBytes(consulta.foto(m.getId()).orElseThrow()))
                .containsExactly((byte) 0xFF, (byte) 0xD8);
        assertThat(consulta.items(m.getId()).foto()).isTrue();
    }

    @Test
    void unTicketPendienteNoSeVe() {
        Movimiento pendiente = ticket("6550", PRODUCTOS, false);

        assertThatThrownBy(() -> consulta.items(pendiente.getId())).isInstanceOf(NoSuchElementException.class);
        assertThat(consulta.foto(pendiente.getId())).isEmpty();
        assertThatThrownBy(() -> consulta.items(999_999L)).isInstanceOf(NoSuchElementException.class);
    }
}
