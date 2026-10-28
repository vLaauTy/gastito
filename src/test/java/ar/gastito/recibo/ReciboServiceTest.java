package ar.gastito.recibo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.SueldoProperties;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.ticket.ImagenInvalidaException;
import ar.gastito.ticket.ImagenTicket;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({IngresoService.class, SueldoService.class, ControlRecibo.class, ReciboServiceTest.Config.class})
class ReciboServiceTest {

    @TestConfiguration
    @EnableConfigurationProperties(SueldoProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            return ValidadorReciboTest.RELOJ;
        }
    }

    static final byte[] FOTO = {1, 2, 3};

    @TempDir
    Path carpeta;

    @Autowired
    IngresoService ingresos;

    @Autowired
    SueldoService sueldo;

    @Autowired
    ControlRecibo control;

    @Autowired
    MovimientoRepository movimientoRepository;

    @Autowired
    ReciboRepository reciboRepository;

    @Autowired
    MapeoConceptoReciboRepository mapeos;

    @Autowired
    PlatformTransactionManager transacciones;

    @Autowired
    Clock clock;

    final LectorRecibo lector = mock(LectorRecibo.class);
    final ImagenTicket imagenes = mock(ImagenTicket.class);
    final ClienteIa ia = mock(ClienteIa.class);
    final Prompts prompts = mock(Prompts.class);
    ArchivosRecibo archivos;
    ReciboService recibos;

    @BeforeEach
    void setUp() {
        archivos = new ArchivosRecibo(carpeta);
        GastitoProperties properties = new GastitoProperties(null, null, null, null,
                new GastitoProperties.Ticket("data", 1568, DataSize.ofMegabytes(1), 4096), null, null);
        recibos = new ReciboService(new LectorPdf(), imagenes, lector, new ValidadorRecibo(clock),
                new MapeadorConceptos(mapeos, ia, prompts), control, sueldo, ingresos, reciboRepository, archivos, properties,
                transacciones, clock);
        when(imagenes.preparar(any())).thenReturn(new ImagenTicket.Imagen(FOTO, 10, 10));
        when(prompts.cargar(anyString(), any())).thenReturn("instrucciones");
        laIaMapea(new MapeoIa(List.of()));
    }

    private void laIaLee(LecturaReciboIa lectura) {
        ClienteIa.Respuesta<LecturaReciboIa> respuesta = new ClienteIa.Respuesta<>(lectura, 7L);
        when(lector.leerTexto(anyString(), any(), any())).thenReturn(respuesta);
        when(lector.leerImagen(any(), any(), any())).thenReturn(respuesta);
    }

    private void laIaMapea(MapeoIa mapeo) {
        when(ia.extraer(anyString(), anyString(), eq(MapeoIa.class), eq(PropositoIa.RECIBO)))
                .thenReturn(new ClienteIa.Respuesta<>(mapeo, 8L));
    }

    private ReciboService.Leido leido(ReciboService.Resultado resultado) {
        assertThat(resultado).isInstanceOf(ReciboService.Leido.class);
        return (ReciboService.Leido) resultado;
    }

    private ReciboService.VistaRecibo vista(ReciboService.Resultado resultado) {
        return leido(resultado).recibo();
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

    private Movimiento cobrar(ClaseIngreso clase, String monto, LocalDate fecha) {
        return ingresos.cargar(clase, new BigDecimal(monto), null, null, fecha);
    }

    @Test
    void unPdfConTextoQuedaGuardadoSinCrearNingunIngreso() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        long movimientosAntes = movimientoRepository.count();

        ReciboService.Leido leido = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), "mi recibo"));

        assertThat(leido.reemplazo()).isFalse();
        assertThat(leido.recibo().neto()).isEqualByComparingTo("1037500");
        assertThat(reciboRepository.count()).isEqualTo(1);
        // El recibo es un control: el ingreso lo carga el usuario cuando cobra.
        assertThat(movimientoRepository.count()).isEqualTo(movimientosAntes);
        verify(lector).leerTexto(anyString(), eq("mi recibo"), any());
        verify(lector, never()).leerImagen(any(), any(), any());
    }

    @Test
    void guardaElDetalleDeConceptosYElArchivo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        byte[] pdf = LectorPdfTest.reciboInventado();

        ReciboService.VistaRecibo vista = vista(recibos.leerPdf(pdf, null));

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
        Path archivo = recibos.archivo(vista.id()).orElseThrow();
        assertThat(archivo).isEqualTo(carpeta.resolve("recibos/2026/10/" + vista.id() + ".pdf").toAbsolutePath()
                .normalize());
        assertThat(Files.readAllBytes(archivo)).isEqualTo(pdf);
    }

    @Test
    void unPdfEscaneadoSeLeeComoImagen() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());

        ReciboService.VistaRecibo vista = vista(recibos.leerPdf(LectorPdfTest.pdf(), null));

        assertThat(vista.neto()).isEqualByComparingTo("1037500");
        verify(lector).leerImagen(eq(FOTO), any(), any());
        verify(lector, never()).leerTexto(anyString(), any(), any());
    }

    @Test
    void unaFotoSeGuardaAchicadaComoJpeg() {
        laIaLee(ValidadorReciboTest.octubre());

        ReciboService.VistaRecibo vista = vista(recibos.leerImagen(new byte[] {9, 9, 9, 9}, "recibo"));

        Path archivo = recibos.archivo(vista.id()).orElseThrow();
        assertThat(archivo.getFileName().toString()).isEqualTo(vista.id() + ".jpg");
        assertThat(archivo).hasBinaryContent(FOTO);
    }

    @Test
    void siNoCierraLaCuentaElAvisoQuedaGuardadoYSaleEnElControl() throws IOException {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        laIaLee(new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), o.periodo(), o.fechaPago(), o.tipo(),
                o.conceptos(), o.bruto(), o.totalDescuentos(), "1.000.000,00"));

        ReciboService.VistaRecibo vista = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(vista.neto()).isEqualByComparingTo("1000000");
        assertThat(vista.aviso()).contains("No cierra");
        assertThat(vista.control().hallazgos()).extracting(ControlRecibo.Hallazgo::tipo)
                .contains(ControlRecibo.Tipo.NO_CIERRA);
    }

    @Test
    void sinNetoNoSeGuardaNada() throws IOException {
        LecturaReciboIa o = ValidadorReciboTest.octubre();
        laIaLee(new LecturaReciboIa("RECIBO_SUELDO", "", o.empleador(), o.periodo(), o.fechaPago(), o.tipo(),
                o.conceptos(), o.bruto(), o.totalDescuentos(), ""));

        ReciboService.Resultado resultado = recibos.leerPdf(LectorPdfTest.reciboInventado(), null);

        assertThat(resultado).isEqualTo(new ReciboService.Ilegible(ValidadorRecibo.Motivo.SIN_NETO));
        assertThat(reciboRepository.count()).isZero();
        assertThat(carpeta.resolve("recibos")).doesNotExist();
    }

    @Test
    void unTicketNoSeGuardaComoRecibo() {
        laIaLee(new LecturaReciboIa("TICKET", "", null, null, null, null, List.of(), null, null, null));

        ReciboService.Resultado resultado = recibos.leerImagen(new byte[] {1}, null);

        assertThat(resultado).isEqualTo(new ReciboService.NoEsRecibo(TipoDocumento.TICKET));
        assertThat(reciboRepository.count()).isZero();
    }

    @Test
    void unArchivoDemasiadoGrandeNoSeProcesa() {
        byte[] enorme = new byte[(int) DataSize.ofMegabytes(1).toBytes() + 1];

        assertThatThrownBy(() -> recibos.leerPdf(enorme, null)).isInstanceOf(ImagenInvalidaException.class);
        assertThatThrownBy(() -> recibos.leerImagen(enorme, null)).isInstanceOf(ImagenInvalidaException.class);
        assertThat(recibos.superaElTamanio(enorme.length)).isTrue();
    }

    // --- Vínculo con el sueldo cobrado ---

    @Test
    void siTodaviaNoCobrasteElReciboQuedaSinVincular() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());

        ReciboService.VistaRecibo vista = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(vista.cobro()).isNull();
        assertThat(vista.control().conCobro()).isFalse();
    }

    @Test
    void elReciboQueLlegaAntesDelCobroSeVinculaCuandoSeCargaElSueldo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();
        assertThat(recibos.cobroDe(id)).isEmpty();

        Movimiento cobro = cobrar(ClaseIngreso.SUELDO, "1037500", LocalDate.of(2026, 10, 30));

        ReciboService.VistaRecibo despues = recibos.vista(id).orElseThrow();
        assertThat(despues.cobro().movimientoId()).isEqualTo(cobro.getId());
        assertThat(despues.cobro().aMano()).isFalse();
        assertThat(despues.control().conCobro()).isTrue();
        assertThat(despues.control().sinNadaRaro()).isTrue();
    }

    @Test
    void siLoCobradoEsDistintoDelNetoDelReciboLoMarca() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();
        cobrar(ClaseIngreso.SUELDO, "1030000", LocalDate.of(2026, 10, 30));

        ControlRecibo.Resultado resultado = recibos.vista(id).orElseThrow().control();

        assertThat(resultado.hallazgos()).extracting(ControlRecibo.Hallazgo::tipo)
                .containsExactly(ControlRecibo.Tipo.NETO_DISTINTO);
        assertThat(resultado.hallazgos().get(0).texto()).contains("$1.037.500", "$1.030.000", "$7.500");
    }

    @Test
    void elAguinaldoYElSueldoNoSeConfunden() throws IOException {
        cobrar(ClaseIngreso.SUELDO, "1030000", LocalDate.of(2026, 6, 30));
        laIaLee(aguinaldo());

        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();
        assertThat(recibos.cobroDe(id)).isEmpty();

        Movimiento sac = cobrar(ClaseIngreso.AGUINALDO, "1037500", LocalDate.of(2026, 6, 30));
        assertThat(recibos.cobroDe(id).orElseThrow().movimientoId()).isEqualTo(sac.getId());
    }

    @Test
    void lasVacacionesYLasLiquidacionesNoBuscanElCobroSolas() throws IOException {
        cobrar(ClaseIngreso.SUELDO, "1030000", LocalDate.of(2026, 10, 30));
        for (String tipo : new String[] {"vacaciones", "liquidacion_final"}) {
            laIaLee(conTipo(tipo));
            Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();

            assertThat(recibos.cobroDe(id)).as(tipo).isEmpty();
        }
    }

    @Test
    void unSueldoCanceladoNoCuentaComoCobro() throws IOException {
        Movimiento cobro = cobrar(ClaseIngreso.SUELDO, "1030000", LocalDate.of(2026, 10, 30));
        ingresos.anular(cobro.getId());
        laIaLee(ValidadorReciboTest.octubre());

        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();

        assertThat(recibos.cobroDe(id)).isEmpty();
    }


    @Test
    void elSueldoDeOctubreCobradoElCuatroDeNoviembreSeVinculaSoloPorPeriodo() throws IOException {
        // Se cobra alrededor del 5 el sueldo del mes anterior: el período del ingreso es octubre.
        Movimiento cobro = cobrar(ClaseIngreso.SUELDO, "1037500", LocalDate.of(2026, 11, 4));
        assertThat(cobro.getPeriodo()).isEqualTo(YearMonth.of(2026, 10));
        laIaLee(ValidadorReciboTest.octubre());

        ReciboService.VistaRecibo vista = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(vista.cobro().movimientoId()).isEqualTo(cobro.getId());
        assertThat(vista.cobro().aMano()).isFalse();
        assertThat(vista.control().sinNadaRaro()).isTrue();
    }

    @Test
    void sePuedeVincularAManoParaCorregirYVolverAlAutomatico() throws IOException {
        // Cargado por error con el período de septiembre: se corrige vinculando a mano.
        Movimiento cobro = cobrar(ClaseIngreso.SUELDO, "1037500", LocalDate.of(2026, 9, 20));
        assertThat(cobro.getPeriodo()).isEqualTo(YearMonth.of(2026, 9));
        laIaLee(ValidadorReciboTest.octubre());
        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();

        ReciboService.VistaRecibo vinculado = recibos.vincular(id, cobro.getId());

        assertThat(vinculado.cobro().movimientoId()).isEqualTo(cobro.getId());
        assertThat(vinculado.cobro().aMano()).isTrue();
        assertThat(recibos.vincular(id, null).cobro()).isNull();
    }

    @Test
    void soloSePuedeVincularUnIngresoGuardado() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();

        assertThatThrownBy(() -> recibos.vincular(id, 999_999L)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Reemplazar y descartar ---

    @Test
    void otroReciboDelMismoMesYTipoReemplazaAlAnteriorYBorraSuArchivo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        ReciboService.VistaRecibo primero = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));
        Path archivoViejo = recibos.archivo(primero.id()).orElseThrow();

        ReciboService.Leido segundo = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(segundo.reemplazo()).isTrue();
        assertThat(reciboRepository.count()).isEqualTo(1);
        assertThat(archivoViejo).doesNotExist();
        assertThat(recibos.archivo(segundo.recibo().id())).isPresent();
    }

    @Test
    void unAguinaldoNoReemplazaAlSueldo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        recibos.leerPdf(LectorPdfTest.reciboInventado(), null);
        laIaLee(aguinaldo());

        ReciboService.Leido sac = leido(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(sac.reemplazo()).isFalse();
        assertThat(reciboRepository.count()).isEqualTo(2);
    }

    @Test
    void descartarBorraElDetalleYElArchivo() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        Long id = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null)).id();
        Path archivo = recibos.archivo(id).orElseThrow();

        recibos.descartar(id);

        assertThat(recibos.vista(id)).isEmpty();
        assertThat(archivo).doesNotExist();
        assertThat(reciboRepository.count()).isZero();
    }

    @Test
    void entreListaLosRecibosDelRangoDelMasViejoAlMasNuevo() throws IOException {
        laIaLee(aguinaldo());
        recibos.leerPdf(LectorPdfTest.reciboInventado(), null);
        laIaLee(ValidadorReciboTest.octubre());
        recibos.leerPdf(LectorPdfTest.reciboInventado(), null);

        assertThat(recibos.entre(YearMonth.of(2026, 1), YearMonth.of(2026, 12)))
                .extracting(ReciboService.VistaRecibo::periodo)
                .containsExactly(YearMonth.of(2026, 6), YearMonth.of(2026, 10));
        assertThat(recibos.entre(YearMonth.of(2026, 7), YearMonth.of(2026, 12))).hasSize(1);
    }

    // --- Qué concepto del sueldo es cada renglón ---

    private Long idDe(String nombre) {
        return sueldo.listarConceptos().stream().filter(c -> c.getNombre().equals(nombre))
                .map(ConceptoSueldo::getId).findFirst().orElseThrow();
    }

    @Test
    void losRenglonesConElNombreDeUnConceptoSeMapeanSinPreguntarleALaIa() throws IOException {
        laIaMapea(new MapeoIa(List.of()));
        laIaLee(ValidadorReciboTest.octubre());

        ReciboService.VistaRecibo vista = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(vista.conceptos()).filteredOn(c -> c.descripcion().equals("Sueldo básico"))
                .singleElement().satisfies(c -> assertThat(c.conceptoId()).isEqualTo(idDe("Sueldo básico")));
    }

    @Test
    void laIaMapeaLoQueNoReconoceYElResultadoSeReusaConElReciboSiguiente() throws IOException {
        laIaLee(ValidadorReciboTest.octubre());
        // Los renglones sin nombre igual al de un concepto, en el orden en que llegan a la IA (el 0 es Antigüedad).
        laIaMapea(new MapeoIa(List.of(new MapeoIa.Renglon(0, "otro"), new MapeoIa.Renglon(1, "Viático"),
                new MapeoIa.Renglon(2, "Jubilación"), new MapeoIa.Renglon(3, "Obra social"),
                new MapeoIa.Renglon(4, "Concepto inventado"))));

        ReciboService.VistaRecibo primera = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        Map<String, Long> mapeados = new java.util.HashMap<>();
        primera.conceptos().forEach(c -> mapeados.put(c.descripcion(), c.conceptoId()));
        assertThat(mapeados.get("Jubilación 11%")).isEqualTo(idDe("Jubilación"));
        assertThat(mapeados.get("Obra social 3%")).isEqualTo(idDe("Obra social"));
        // Un nombre que no está en la lista no se acepta: queda como "otro".
        assertThat(mapeados.get("Ley 19.032 3%")).isNull();
        verify(ia, times(1)).extraer(anyString(), anyString(), eq(MapeoIa.class), eq(PropositoIa.RECIBO));

        // El mes siguiente: lo que la IA ya dijo está guardado y no se vuelve a preguntar.
        ReciboService.VistaRecibo segunda = vista(recibos.leerPdf(LectorPdfTest.reciboInventado(), null));

        assertThat(segunda.conceptos()).filteredOn(c -> c.descripcion().equals("Jubilación 11%"))
                .singleElement().satisfies(c -> assertThat(c.conceptoId()).isEqualTo(idDe("Jubilación")));
        verify(ia, times(1)).extraer(anyString(), anyString(), eq(MapeoIa.class), eq(PropositoIa.RECIBO));
    }
}
