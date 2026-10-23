package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.interpretacion.HistorialCategorias;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;

class TicketServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 22, 16, 0);
    static final long ID = 15;
    static final long USO_IA = 7;

    @TempDir
    Path datos;

    private final LectorTicket lector = mock(LectorTicket.class);
    private final HistorialCategorias historial = mock(HistorialCategorias.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final MovimientoService movimientos = mock(MovimientoService.class);
    private final RegistroUsoIa usoIa = mock(RegistroUsoIa.class);
    private TicketService service;

    @BeforeEach
    void setUp() {
        service = servicio(DataSize.ofMegabytes(10));
        when(catalogos.cargar()).thenReturn(CatalogoDePrueba.crear());
        when(historial.buscar(anyString(), any())).thenReturn(Optional.empty());
        when(movimientos.crearPendienteDeTicket(any(), any(), anyList(), any())).thenAnswer(invocacion -> {
            Movimiento m = Movimiento.deTicket(invocacion.getArgument(0), invocacion.getArgument(1),
                    invocacion.getArgument(2), invocacion.getArgument(3), AHORA);
            ReflectionTestUtils.setField(m, "id", ID);
            when(movimientos.buscar(ID)).thenReturn(m);
            return m;
        });
    }

    private TicketService servicio(DataSize tamanioMaximo) {
        GastitoProperties properties = new GastitoProperties(null, null, null, null,
                new GastitoProperties.Ticket(datos.toString(), 1568, tamanioMaximo, 4096));
        return new TicketService(new ImagenTicket(1568), lector, new ValidadorTicket(new ResolvedorFechas()),
                historial, catalogos, movimientos, usoIa, new FotosTicket(datos), properties,
                Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA));
    }

    private void laIaLee(LecturaTicketIa lectura) {
        when(lector.leer(any(), any(), any(), any())).thenReturn(new ClienteIa.Respuesta<>(lectura, USO_IA));
    }

    @Test
    void unTicketQuedaPendienteConSusProductosYLaFotoGuardada() throws IOException {
        laIaLee(ticket("8050"));

        TicketService.Resultado resultado = service.leer(jpeg(3000, 4000), " con débito ");

        assertThat(resultado).isInstanceOf(TicketService.Leido.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ItemTicket>> items = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<MovimientoInterpretado> interpretado = ArgumentCaptor.forClass(MovimientoInterpretado.class);
        verify(movimientos).crearPendienteDeTicket(interpretado.capture(), eq("Supermercado Día"), items.capture(),
                eq("con débito"));
        assertThat(interpretado.getValue().monto()).isEqualByComparingTo("8050");
        assertThat(interpretado.getValue().fechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 18, 17, 20));
        assertThat(items.getValue()).hasSize(3);
        verify(usoIa).asociarMovimiento(USO_IA, ID);

        // La foto queda en tickets/AAAA/MM/<id>.jpg según la fecha del ticket, achicada.
        verify(movimientos).asociarFotoTicket(ID, "tickets/2026/10/15.jpg");
        Path foto = datos.resolve("tickets/2026/10/15.jpg");
        assertThat(foto).exists();
        BufferedImage guardada = ImageIO.read(foto.toFile());
        assertThat(guardada.getHeight()).isEqualTo(1568);
        assertThat(guardada.getWidth()).isEqualTo(1176);
    }

    @Test
    void aLaIaLeLlegaLaFotoAchicadaYLaPista() throws IOException {
        laIaLee(ticket("8050"));

        service.leer(jpeg(4000, 3000), "efectivo");

        ArgumentCaptor<byte[]> enviada = ArgumentCaptor.forClass(byte[].class);
        verify(lector).leer(enviada.capture(), eq("efectivo"), any(), eq(AHORA));
        BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(enviada.getValue()));
        assertThat(Math.max(imagen.getWidth(), imagen.getHeight())).isEqualTo(1568);
    }

    @Test
    void sinTextoElTextoOriginalQuedaVacio() throws IOException {
        laIaLee(ticket("8050"));

        service.leer(jpeg(800, 600), "  ");

        verify(movimientos).crearPendienteDeTicket(any(), any(), anyList(), isNull());
    }

    @Test
    void laCategoriaQueYaEligioElUsuarioParaEseComercioMandaSobreLaIa() throws IOException {
        laIaLee(ticket("8050"));
        when(historial.buscar(eq("Supermercado Día"), any()))
                .thenReturn(Optional.of(new HistorialCategorias.Aprendido(CatalogoDePrueba.KIOSCO, "Supermercado Día")));

        service.leer(jpeg(800, 600), null);

        ArgumentCaptor<MovimientoInterpretado> interpretado = ArgumentCaptor.forClass(MovimientoInterpretado.class);
        verify(movimientos).crearPendienteDeTicket(interpretado.capture(), any(), anyList(), any());
        assertThat(interpretado.getValue().categoria()).isEqualTo(CatalogoDePrueba.KIOSCO);
    }

    @Test
    void unaFotoQueNoEsTicketNoGuardaNada() throws IOException {
        laIaLee(new LecturaTicketIa("no_es_ticket", "", "", "", List.of(), List.of(), "", "", "", List.of(), ""));

        assertThat(service.leer(jpeg(800, 600), null)).isInstanceOf(TicketService.NoEsTicket.class);
        verify(movimientos, never()).crearPendienteDeTicket(any(), any(), anyList(), any());
        assertThat(datos.resolve("tickets")).doesNotExist();
    }

    @Test
    void unTicketSinTotalNoGuardaNada() throws IOException {
        laIaLee(ticket(""));

        assertThat(service.leer(jpeg(800, 600), null))
                .isEqualTo(new TicketService.Ilegible(ValidadorTicket.Motivo.SIN_TOTAL));
        verify(movimientos, never()).crearPendienteDeTicket(any(), any(), anyList(), any());
    }

    @Test
    void unaImagenMuyPesadaNoSeMandaALaIa() throws IOException {
        TicketService chico = servicio(DataSize.ofKilobytes(1));

        assertThatThrownBy(() -> chico.leer(jpeg(800, 600), null))
                .isInstanceOf(ImagenInvalidaException.class)
                .extracting(e -> ((ImagenInvalidaException) e).getMotivo())
                .isEqualTo(ImagenInvalidaException.Motivo.DEMASIADO_GRANDE);
        verifyNoInteractions(lector);
        assertThat(chico.superaElTamanio(1025)).isTrue();
        assertThat(chico.superaElTamanio(1024)).isFalse();
    }

    @Test
    void siLaIaNoEstaDisponibleNoGuardaNada() throws IOException {
        when(lector.leer(any(), any(), any(), any())).thenThrow(new IaNoDisponibleException("sin red", null));

        assertThatThrownBy(() -> service.leer(jpeg(800, 600), null)).isInstanceOf(IaNoDisponibleException.class);
        verify(movimientos, never()).crearPendienteDeTicket(any(), any(), anyList(), any());
    }

    @Test
    void alCancelarSeBorraLaFoto() throws IOException {
        laIaLee(ticket("8050"));
        service.leer(jpeg(800, 600), null);
        Movimiento m = movimientos.buscar(ID);
        m.asociarFotoTicket("tickets/2026/10/15.jpg");
        Path foto = datos.resolve("tickets/2026/10/15.jpg");
        assertThat(foto).exists();

        service.borrarFoto(m);

        assertThat(foto).doesNotExist();
        verify(movimientos).quitarFotoTicket(ID);
    }

    @Test
    void lasFotosNoSeSirvenFueraDeLaCarpetaDeTickets() throws IOException {
        Files.writeString(datos.resolve("gastito.mv.db"), "base");
        FotosTicket fotos = new FotosTicket(datos);

        assertThat(fotos.archivo("tickets/../gastito.mv.db")).isEmpty();
        assertThat(fotos.archivo("../secreto.jpg")).isEmpty();
        assertThat(fotos.archivo("tickets/2026/10/99.jpg")).isEmpty(); // no existe
        assertThat(fotos.archivo(null)).isEmpty();
    }

    private static LecturaTicketIa ticket(String total) {
        return new LecturaTicketIa("", "Supermercado Día", "18/10/26", "17:20",
                List.of(new LecturaTicketIa.Item("Leche 1 L", "2", "1450", "2900"),
                        new LecturaTicketIa.Item("Yerba 500 g", "", "", "3900"),
                        new LecturaTicketIa.Item("Galletitas", "", "", "1250")),
                List.of(), total, "Débito", "", List.of("Supermercado"), "alta");
    }

    private static byte[] jpeg(int ancho, int alto) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        return bytes.toByteArray();
    }
}
