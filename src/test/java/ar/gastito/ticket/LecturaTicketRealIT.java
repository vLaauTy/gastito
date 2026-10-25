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
import java.time.LocalDateTime;
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
        // El ticket real está arrugado: la IA puede marcar líneas tapadas por un pliegue aunque las haya leído bien.
        // Se imprimen (arriba) y no fallan: la tarjeta solo las muestra si las cuentas no cierran.
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

    /** Un ticket dibujado con el formato de Día. Los nombres y números son inventados. */
    @Test
    void leeUnTicketConElFormatoDeDiaSinDatosSensibles() throws IOException {
        TicketService.Resultado r = tickets.leer(ticket(List.of(
                "PEREZ JUAN CARLOS",
                "A CUENTA Y ORDEN DE DIA ARG. SA",
                "CUIT 20-12345678-6",
                "Sucursal 5601  Av. Siempre Viva 742",
                "TIQUE FACTURA B   Nro 0012-00034567",
                "22-09-2026 17:44:28",
                "--------------------------------",
                "2 X 1.450,00",
                "LECHE ENT 1L (21,00)       2.900,00",
                "7791813777021",
                "1 X 3.900,00",
                "YERBA MATE 500G (21,00)    3.900,00",
                "7790070432858",
                "20% ClubDia                 -780,00",
                "1 X 1.250,00",
                "GALLET DULCES (21,00)      1.250,00",
                "7792180145444",
                "--------------------------------",
                "SUBTOTAL                   8.050,00",
                "DESCUENTOS                  -780,00",
                "TOTAL                 $     7270.00",
                "TARJETA                    7.270,00",
                "IVA CONTENIDO              1.261,74",
                "Ahorro ClubDia               780,00",
                "Cajero: GOMEZ MARIA  Caja 3",
                "ClubDia N 4455667788")), null);

        assertThat(r).isInstanceOf(TicketService.Leido.class);
        Movimiento m = ((TicketService.Leido) r).movimiento();
        imprimir(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(m.getComercio()).as("comercio: a cuenta y orden de Día").isEqualTo("Día");
        s.assertThat(m.getSucursal()).as("sucursal").isEqualTo("5601");
        s.assertThat(m.getFechaHora()).as("fecha con guiones").isEqualTo(LocalDateTime.of(2026, 9, 22, 17, 44));
        s.assertThat(m.getMonto()).as("total").isEqualByComparingTo("7270");
        s.assertThat(m.getSubtotalImpreso()).as("subtotal").isEqualByComparingTo("8050");
        s.assertThat(m.getDescuentosImpresos()).as("descuentos").isEqualByComparingTo("-780");
        s.assertThat(ControlesTicket.revisar(m)).as("cuentas que no cierran").isEmpty();
        s.assertThat(m.getMedioPago()).as("TARJETA sin aclarar").isNull();
        s.assertThat(m.getItems()).extracting(MovimientoItem::getSubtotal).as("renglones en orden")
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2900"), new BigDecimal("3900"), new BigDecimal("-780"),
                        new BigDecimal("1250"));
        s.assertThat(m.getItems()).extracting(MovimientoItem::getCodigoBarras).as("códigos de barras")
                .containsExactly("7791813777021", "7790070432858", null, "7792180145444");
        s.assertThat(m.getItems().get(0).getCantidad()).as("cantidad").isEqualByComparingTo("2");
        String guardado = m.getComercio() + m.getDescripcion() + m.getSucursal()
                + m.getItems().stream().map(i -> i.getDescripcion() + i.getDescripcionOriginal()).toList()
                + m.getLineasIlegibles();
        s.assertThat(guardado).as("datos personales")
                .doesNotContainIgnoringCase("perez").doesNotContainIgnoringCase("gomez")
                .doesNotContain("12345678").doesNotContain("4455667788").doesNotContain("(21,00)");
        s.assertThat(m.getFotoTicket()).startsWith("tickets/");
        s.assertAll();
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
