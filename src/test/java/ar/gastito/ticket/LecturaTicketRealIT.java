package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/**
 * Lee un ticket dibujado en el test con la API real de Anthropic y la key del .env (cuesta fracciones de
 * centavo). No corre con "mvn test". Para correrlo, por ejemplo después de cambiar el prompt:
 *
 * <pre>mvn test -Dtest=LecturaTicketRealIT -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@SpringBootTest(properties = {"gastito.ia.api-key=${ANTHROPIC_API_KEY}",
        "gastito.ticket.carpeta-datos=target/ticket-real-it"})
@ActiveProfiles("test")
class LecturaTicketRealIT {

    @Autowired
    TicketService tickets;

    @Autowired
    RegistroUsoIa registro;

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
