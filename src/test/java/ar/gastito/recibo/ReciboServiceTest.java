package ar.gastito.recibo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.InicioRegistro;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.ImagenTicket;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({IngresoService.class, MovimientoService.class, ReciboServiceTest.Reloj.class})
class ReciboServiceTest {

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return ValidadorReciboTest.RELOJ;
        }
    }

    static final byte[] FOTO = {1, 2, 3};

    @Autowired
    IngresoService ingresos;

    @Autowired
    MovimientoService movimientos;

    @Autowired
    MovimientoRepository movimientoRepository;

    @Autowired
    ReciboRepository reciboRepository;

    @Autowired
    Clock clock;

    @TempDir
    Path carpeta;

    final LectorRecibo lector = mock(LectorRecibo.class);
    final ImagenTicket imagenes = mock(ImagenTicket.class);
    final RegistroUsoIa usoIa = mock(RegistroUsoIa.class);
    ArchivosRecibo archivos;
    ReciboService recibos;

    @BeforeEach
    void setUp() {
        archivos = new ArchivosRecibo(carpeta);
        GastitoProperties properties = new GastitoProperties(null, null, null, null,
                new GastitoProperties.Ticket("data", 1568, DataSize.ofMegabytes(1), 4096), null, null);
        recibos = new ReciboService(new LectorPdf(), imagenes, lector, new ValidadorRecibo(clock), ingresos,
                movimientos, reciboRepository, usoIa, archivos, properties, clock);
        when(imagenes.preparar(any())).thenReturn(new ImagenTicket.Imagen(FOTO, 10, 10));
    }

    private void laIaLee(LecturaReciboIa lectura) {
        ClienteIa.Respuesta<LecturaReciboIa> respuesta = new ClienteIa.Respuesta<>(lectura, 7L);
        when(lector.leerTexto(anyString(), any(), any())).thenReturn(respuesta);
        when(lector.leerImagen(any(), any(), any())).thenReturn(respuesta);
    }

    private Movimiento leido(ReciboService.Resultado resultado) {
        assertThat(resultado).isInstanceOf(ReciboService.Leido.class);
        return ((ReciboService.Leido) resultado).movimiento();
    }

    private static LecturaReciboIa aguinaldo() {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        return new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), "2026-06", "30/06/2026", "aguinaldo",
                o.conceptos(), o.bruto(), o.totalDescuentos(), o.neto());
    }

    private static LecturaReciboIa conTipo(String tipo) {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        return new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), o.periodo(), o.fechaPago(), tipo,
                o.conceptos(), o.bruto(), o.totalDescuentos(), o.neto());
    }

    private Movimiento sueldoALaMano(String monto) {
        return ingresos.cargar(ClaseIngreso.SUELDO, new BigDecimal(monto), null, null, LocalDate.of(2026, 10, 1));
    }

    @Test
    void unPdfConTextoQuedaComoIngresoPendientePorElNeto() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());

        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), "mi recibo"));

        assertThat(m.getEstado()).isEqualTo(EstadoMovimiento.PENDIENTE);
        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.INGRESO);
        assertThat(m.getClaseIngreso()).isEqualTo(ClaseIngreso.SUELDO);
        assertThat(m.getOrigen()).isEqualTo(Origen.RECIBO);
        assertThat(m.getMonto()).isEqualByComparingTo("1037500");
        assertThat(m.getDescripcion()).isEqualTo("Sueldo octubre 2026 · Empresa Ejemplo");
        // El pago fue el 3 de noviembre, pero el sueldo es de octubre.
        assertThat(m.getFechaHora().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        verify(lector).leerTexto(anyString(), eq("mi recibo"), any());
        verify(lector, never()).leerImagen(any(), any(), any());
        verify(usoIa).asociarMovimiento(7L, m.getId());
    }

    @Test
    void guardaElDetalleDeConceptosYElArchivo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        byte[] pdf = LectorPdfTest.reciboInventado();

        Movimiento m = leido(recibos.leerPdf(pdf, null));

        ReciboService.VistaRecibo vista = recibos.vista(m.getId()).orElseThrow();
        assertThat(vista.empleador()).isEqualTo("Empresa Ejemplo");
        assertThat(vista.periodo()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(vista.tipo()).isEqualTo(TipoRecibo.SUELDO_MENSUAL);
        assertThat(vista.bruto()).isEqualByComparingTo("1250000");
        assertThat(vista.descuentos()).isEqualByComparingTo("212500");
        assertThat(vista.neto()).isEqualByComparingTo("1037500");
        assertThat(vista.conceptos()).extracting(ReciboService.Renglon::descripcion).containsExactly("Sueldo básico",
                "Antigüedad", "Suma no remunerativa", "Jubilación 11%", "Obra social 3%", "Ley 19.032 3%");
        assertThat(vista.aviso()).isNull();
        assertThat(vista.conArchivo()).isTrue();
        Path archivo = recibos.archivo(m.getId()).orElseThrow();
        assertThat(archivo).isEqualTo(carpeta.resolve("recibos/2026/10/" + m.getId() + ".pdf").toAbsolutePath()
                .normalize());
        assertThat(Files.readAllBytes(archivo)).isEqualTo(pdf);
    }

    @Test
    void unPdfEscaneadoSeLeeComoImagen() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());

        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.pdf(), null));

        assertThat(m.getMonto()).isEqualByComparingTo("1037500");
        verify(lector).leerImagen(eq(FOTO), any(), any());
        verify(lector, never()).leerTexto(anyString(), any(), any());
    }

    @Test
    void unaFotoSeGuardaAchicadaComoJpeg() {
        laIaLee(ValidadorReciboTest.octubre());

        Movimiento m = leido(recibos.leerImagen(new byte[] {9, 9, 9, 9}, "recibo"));

        Path archivo = recibos.archivo(m.getId()).orElseThrow();
        assertThat(archivo.getFileName().toString()).isEqualTo(m.getId() + ".jpg");
        assertThat(archivo).hasBinaryContent(FOTO);
    }

    @Test
    void unAguinaldoEsUnIngresoDeClaseAguinaldo() throws IOException {
        laIaLee(aguinaldo());

        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(m.getClaseIngreso()).isEqualTo(ClaseIngreso.AGUINALDO);
        assertThat(m.getDescripcion()).startsWith("Aguinaldo junio 2026");
        assertThat(m.getFechaHora().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void unaFechaDePagoEnElMesDelPeriodoEsLaFechaDelIngreso() throws IOException {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        laIaLee(new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), "2026-10", "29/10/2026", "sueldo_mensual",
                o.conceptos(), o.bruto(), o.totalDescuentos(), o.neto()));

        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(m.getFechaHora().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    void unPeriodoQueTerminaEnElFuturoNoPoneElIngresoEnElFuturo() throws IOException {
        // Hoy es 5/11/2026: el sueldo de noviembre todavía no se terminó.
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        laIaLee(new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), "2026-11", "", "sueldo_mensual",
                o.conceptos(), o.bruto(), o.totalDescuentos(), o.neto()));

        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(m.getFechaHora().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void siNoCierraLaCuentaElAvisoQuedaGuardado() throws IOException {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        laIaLee(new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), o.periodo(), o.fechaPago(), o.tipo(),
                o.conceptos(), o.bruto(), o.totalDescuentos(), "1.000.000,00"));

        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(m.getMonto()).isEqualByComparingTo("1000000");
        assertThat(recibos.vista(m.getId()).orElseThrow().aviso()).contains("No cierra");
    }

    @Test
    void sinNetoNoSeGuardaNada() throws IOException {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        laIaLee(new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), o.periodo(), o.fechaPago(), o.tipo(),
                o.conceptos(), o.bruto(), o.totalDescuentos(), ""));
        long antes = movimientoRepository.count();

        ReciboService.Resultado resultado = recibos.leerPdf(LectorPdfTest.reciboInventado(), null);

        assertThat(resultado).isEqualTo(new ReciboService.Ilegible(ValidadorRecibo.Motivo.SIN_NETO));
        assertThat(movimientoRepository.count()).isEqualTo(antes);
        assertThat(reciboRepository.count()).isZero();
        assertThat(carpeta.resolve("recibos")).doesNotExist();
    }

    @Test
    void unTicketNoSeGuardaComoRecibo() throws IOException {
        laIaLee(new LecturaReciboIa("TICKET", "", null, null, null, null, List.of(), null, null, null));
        long antes = movimientoRepository.count();

        ReciboService.Resultado resultado = recibos.leerImagen(new byte[] {1}, null);

        assertThat(resultado).isEqualTo(new ReciboService.NoEsRecibo(TipoDocumento.TICKET));
        assertThat(movimientoRepository.count()).isEqualTo(antes);
    }

    @Test
    void unArchivoDemasiadoGrandeNoSeProcesa() {
        byte[] enorme = new byte[(int) DataSize.ofMegabytes(1).toBytes() + 1];

        assertThatThrownBy(() -> recibos.leerPdf(enorme, null)).isInstanceOf(ImagenInvalidaException.class);
        assertThatThrownBy(() -> recibos.leerImagen(enorme, null)).isInstanceOf(ImagenInvalidaException.class);
        assertThat(recibos.superaElTamanio(enorme.length)).isTrue();
    }

    // --- Confirmación ---

    @Test
    void sinSueldoCargadoEseMesSeGuardaDirecto() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(recibos.yaCargado(m.getId())).isEmpty();
        MovimientoService.Confirmacion c = movimientos.confirmar(m.getId());

        assertThat(c.movimiento().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
    }

    @Test
    void siYaCargasteElSueldoDeEseMesLoPreguntaConSuMonto() throws IOException {
        Movimiento manual = sueldoALaMano("1030000");
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        Movimiento anterior = recibos.yaCargado(m.getId()).orElseThrow();

        assertThat(anterior.getId()).isEqualTo(manual.getId());
        assertThat(anterior.getMonto()).isEqualByComparingTo("1030000");
    }

    @Test
    void alReemplazarSeGuardaElDelReciboYElAnteriorQuedaCancelado() throws IOException {
        Movimiento manual = sueldoALaMano("1030000");
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        MovimientoService.Confirmacion c = recibos.confirmarReemplazando(m.getId());

        assertThat(c.movimiento().getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        // Del sueldo de octubre queda un solo ingreso: el del recibo.
        assertThat(c.totalMesCategoria()).isEqualByComparingTo("1037500");
        assertThat(movimientoRepository.findById(manual.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoMovimiento.CANCELADO);
        assertThat(ingresos.totalDelMes(YearMonth.of(2026, 10))).isEqualByComparingTo("1037500");
    }

    @Test
    void elAguinaldoYElSueldoNoSeConfunden() throws IOException {
        sueldoALaMano("1030000");
        laIaLee(aguinaldo());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        // El sueldo de octubre no es el aguinaldo de junio.
        assertThat(recibos.yaCargado(m.getId())).isEmpty();
    }

    @Test
    void lasVacacionesYLasLiquidacionesNoPreguntanNada() throws IOException {
        sueldoALaMano("1030000");
        for (String tipo : new String[] {"vacaciones", "liquidacion_final"}) {
            laIaLee(conTipo(tipo));
            Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

            assertThat(recibos.yaCargado(m.getId())).as(tipo).isEmpty();
        }
    }

    @Test
    void unSueldoCanceladoNoCuentaComoCargado() throws IOException {
        Movimiento manual = sueldoALaMano("1030000");
        ingresos.anular(manual.getId());
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(recibos.yaCargado(m.getId())).isEmpty();
    }

    @Test
    void alCancelarSeBorraElDetalleYElArchivo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));
        Path archivo = recibos.archivo(m.getId()).orElseThrow();

        movimientos.cancelar(m.getId());
        recibos.descartar(m.getId());

        assertThat(recibos.vista(m.getId())).isEmpty();
        assertThat(archivo).doesNotExist();
        assertThat(reciboRepository.count()).isZero();
    }

    @Test
    void unReciboNoMarcaElInicioDelRegistro() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento m = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));
        movimientos.confirmar(m.getId());
        InicioRegistro inicio = new InicioRegistro(movimientoRepository);

        // Un recibo de octubre no dice que se empezó a anotar gastos en octubre: los meses de antes no son $0.
        assertThat(inicio.primerDia()).isEmpty();
        assertThat(inicio.hayDatosDesde(LocalDate.of(2026, 10, 1))).isFalse();
    }

    @Test
    void soloSeListanLosRecibosConIngresoGuardado() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Movimiento guardado = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));
        movimientos.confirmar(guardado.getId());
        leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        List<ReciboService.VistaRecibo> lista = recibos.guardadosEntre(YearMonth.of(2026, 1), YearMonth.of(2026, 12));

        assertThat(lista).extracting(ReciboService.VistaRecibo::movimientoId).containsExactly(guardado.getId());
        assertThat(LocalDateTime.now(clock).getYear()).isEqualTo(2026);
    }
}
