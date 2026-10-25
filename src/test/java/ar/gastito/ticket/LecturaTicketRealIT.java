package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/**
 * Lee tickets con la API real de Anthropic y la key del .env (cuesta fracciones de centavo por lectura). No
 * corre con "mvn test". Para correrlo, por ejemplo después de cambiar el prompt:
 *
 * <pre>mvn test -Dtest=LecturaTicketRealIT -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * La foto del ticket real de Día no está en el repo: se pone en {@value #CARPETA_FOTOS} (adentro de data/, que
 * está en el .gitignore) como dia-2026-09-22.jpg (o .jpeg, .png). Sin la foto, ese test se saltea.
 */
@SpringBootTest(properties = {"gastito.ia.api-key=${ANTHROPIC_API_KEY}",
        "gastito.ticket.carpeta-datos=target/ticket-real-it"})
@ActiveProfiles("test")
class LecturaTicketRealIT {

    static final String CARPETA_FOTOS = "data/tickets-prueba";
    static final String FOTO_DIA = "dia-2026-09-22";

    @Autowired
    TicketService tickets;

    @Autowired
    RegistroUsoIa registro;

    /** La foto real de Día contra la lectura esperada que armó el usuario. */
    @Test
    void laFotoRealDeDiaDaLaLecturaEsperada() throws IOException {
        Optional<Path> foto = Stream.of(".jpg", ".jpeg", ".png", ".JPG", ".JPEG", ".PNG")
                .map(extension -> Path.of(CARPETA_FOTOS, FOTO_DIA + extension))
                .filter(Files::isRegularFile)
                .findFirst();
        assumeTrue(foto.isPresent(), "Falta la foto " + CARPETA_FOTOS + "/" + FOTO_DIA + ".jpg: el test se saltea");
        TicketDiaEsperado esperado = TicketDiaEsperado.cargar();

        TicketService.Resultado r = tickets.leer(Files.readAllBytes(foto.get()), null);

        assertThat(r).isInstanceOf(TicketService.Leido.class);
        Movimiento m = ((TicketService.Leido) r).movimiento();
        imprimir(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(m.getMonto()).as("total").isEqualByComparingTo(esperado.total);
        s.assertThat(m.getSubtotalImpreso()).as("subtotal impreso").isEqualByComparingTo(esperado.subtotal);
        s.assertThat(m.getDescuentosImpresos()).as("descuentos impresos").isEqualByComparingTo(esperado.descuentos);
        s.assertThat(ControlesTicket.revisar(m)).as("cuentas que no cierran").isEmpty();
        s.assertThat(m.getFechaHora()).as("fecha y hora").isEqualTo(esperado.fechaHora);
        s.assertThat(m.getComercio()).as("comercio").isEqualTo(esperado.comercio);
        s.assertThat(m.getSucursal()).as("sucursal").isEqualTo(esperado.sucursal);
        s.assertThat(m.getMedioPago()).as("medio de pago (el ticket dice solo TARJETA)").isNull();
        s.assertThat(m.getItems()).as("renglones").hasSameSizeAs(esperado.renglones);
        for (int i = 0; i < Math.min(m.getItems().size(), esperado.renglones.size()); i++) {
            MovimientoItem leido = m.getItems().get(i);
            TicketDiaEsperado.Renglon renglon = esperado.renglones.get(i);
            s.assertThat(leido.getSubtotal()).as("subtotal del renglón %d (%s)", i, renglon.descripcion())
                    .isEqualByComparingTo(renglon.subtotal());
            s.assertThat(leido.isDescuento()).as("renglón %d es descuento", i).isEqualTo(renglon.esDescuento());
            s.assertThat(leido.getCodigoBarras()).as("código de barras del renglón %d", i)
                    .isEqualTo(renglon.codigoBarras());
            s.assertThat(leido.getCantidad() == null ? BigDecimal.ONE : leido.getCantidad())
                    .as("cantidad del renglón %d", i).isEqualByComparingTo(renglon.cantidad());
        }
        s.assertThat(m.getLineasIlegibles()).as("líneas ilegibles").isEmpty();
        s.assertAll();
        System.out.println("Uso del mes: " + registro.resumenMesActual());
    }

    private static void imprimir(Movimiento m) {
        System.out.printf("Leído: %s (sucursal %s), %s, subtotal %s, descuentos %s, total %s, medio %s%n",
                m.getComercio(), m.getSucursal(), m.getFechaHora(), m.getSubtotalImpreso(), m.getDescuentosImpresos(),
                m.getMonto(), m.getMedioPago() == null ? "-" : m.getMedioPago().getNombre());
        for (MovimientoItem i : m.getItems()) {
            System.out.printf("  %-36s %-22s %-13s %10s%n", i.getDescripcion(),
                    i.getDescripcionOriginal() == null ? "" : i.getDescripcionOriginal(),
                    i.getCodigoBarras() == null ? "" : i.getCodigoBarras(), i.getSubtotal());
        }
        m.getLineasIlegibles().forEach(l -> System.out.println("  No se leyó: " + l));
    }

    @Test
    void leeUnTicketDeSupermercadoSinDatosSensibles() throws IOException {
        TicketService.Resultado r = tickets.leer(ticket(List.of(
                "SUPERMERCADO DIA",
                "DIA ARGENTINA S.A.",
                "CUIT 30-68731043-4",
                "Av. Siempre Viva 742 - CABA",
                "FACTURA B   Nro 0012-00034567",
                "Fecha: 18/10/26     Hora: 17:20",
                "--------------------------------",
                "2 x 1.450,00",
                "LECHE ENTERA 1L           2.900,00",
                "YERBA MATE 500G           3.900,00",
                "GALLETITAS DULCES         1.250,00",
                "DTO 2X1 GALLETITAS         -250,00",
                "--------------------------------",
                "TOTAL                 $  7.800,00",
                "TARJETA DEBITO VISA  ****4321",
                "IVA CONTENIDO 21%         1.353,72",
                "Le atendio: MARIA GOMEZ")), null);

        assertThat(r).isInstanceOf(TicketService.Leido.class);
        Movimiento m = ((TicketService.Leido) r).movimiento();
        System.out.printf("Leído: %s, %s, total %s, %s, %s, productos %s%n", m.getComercio(), m.getFechaHora(),
                m.getMonto(), m.getMedioPago() == null ? "-" : m.getMedioPago().getNombre(),
                m.getCategoria() == null ? "candidatas " + m.getCandidatas().stream().map(c -> c.getNombre()).toList()
                        : m.getCategoria().getNombre(),
                m.getItems().stream().map(i -> i.getDescripcion() + " " + i.getSubtotal()).toList());
        assertThat(m.getMonto()).isEqualByComparingTo("7800");
        assertThat(m.getComercio()).containsIgnoringCase("d");
        assertThat(m.getFechaHora().toLocalDate()).hasDayOfMonth(18).hasMonthValue(10);
        assertThat(m.getMedioPago().getNombre()).isEqualTo("Débito");
        assertThat(m.getItems().stream().map(MovimientoItem::getSubtotal).reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add)).isEqualByComparingTo("7800");
        assertThat(m.getItems()).anySatisfy(i -> assertThat(i.isDescuento()).isTrue());
        String guardado = m.getComercio() + m.getDescripcion() + m.getItems().stream()
                .map(MovimientoItem::getDescripcion).toList();
        assertThat(guardado).doesNotContain("4321").doesNotContain("68731043").doesNotContainIgnoringCase("maria");
        assertThat(m.getFotoTicket()).startsWith("tickets/");
        System.out.println("Uso del mes: " + registro.resumenMesActual());
    }

    @Test
    void unaFotoQueNoEsUnTicket() throws IOException {
        BufferedImage imagen = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        g.setColor(new Color(90, 160, 230));
        g.fillRect(0, 0, 800, 600);
        g.setColor(new Color(250, 210, 60));
        g.fillOval(560, 60, 160, 160);
        g.setColor(new Color(60, 140, 70));
        g.fillRect(0, 420, 800, 180);
        g.dispose();

        assertThat(tickets.leer(jpeg(imagen), null)).isInstanceOf(TicketService.NoEsTicket.class);
    }

    private static byte[] ticket(List<String> renglones) throws IOException {
        BufferedImage imagen = new BufferedImage(620, 60 + renglones.size() * 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, imagen.getWidth(), imagen.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 24));
        for (int i = 0; i < renglones.size(); i++) {
            g.drawString(renglones.get(i), 30, 60 + i * 40);
        }
        g.dispose();
        return jpeg(imagen);
    }

    private static byte[] jpeg(BufferedImage imagen) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(imagen, "jpeg", bytes);
        return bytes.toByteArray();
    }
}
