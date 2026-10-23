package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImagenTicketTest {

    private final ImagenTicket imagenes = new ImagenTicket(1568);

    @Test
    void unaFotoGrandeQuedaDentroDelLimiteYConservaLaProporcion() throws IOException {
        ImagenTicket.Imagen resultado = imagenes.preparar(jpeg(4000, 3000));

        assertThat(resultado.ancho()).isEqualTo(1568);
        assertThat(resultado.alto()).isEqualTo(1176);
        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(resultado.jpeg()));
        assertThat(leida.getWidth()).isEqualTo(1568);
        assertThat(leida.getHeight()).isEqualTo(1176);
    }

    @Test
    void unTicketLargoYAngostoSeAchicaPorElAlto() throws IOException {
        ImagenTicket.Imagen resultado = imagenes.preparar(jpeg(1200, 6000));

        assertThat(resultado.alto()).isEqualTo(1568);
        assertThat(resultado.ancho()).isEqualTo(314); // 1200 × 1568 / 6000 = 313,6
    }

    @Test
    void unaFotoChicaNoSeAgranda() throws IOException {
        ImagenTicket.Imagen resultado = imagenes.preparar(jpeg(800, 600));

        assertThat(resultado.ancho()).isEqualTo(800);
        assertThat(resultado.alto()).isEqualTo(600);
    }

    @Test
    void unPngConTransparenciaQuedaEnJpegSobreBlanco() throws IOException {
        BufferedImage png = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB); // todo transparente
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(png, "png", bytes);

        ImagenTicket.Imagen resultado = imagenes.preparar(bytes.toByteArray());

        assertThat(resultado.jpeg()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(resultado.jpeg()[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(resultado.jpeg()));
        Color pixel = new Color(leida.getRGB(50, 25));
        assertThat(pixel.getRed()).isGreaterThan(245);
        assertThat(pixel.getGreen()).isGreaterThan(245);
        assertThat(pixel.getBlue()).isGreaterThan(245);
    }

    @Test
    void enderezaUnaFotoGiradaSegunElExif() throws IOException {
        // 400 × 200: mitad izquierda roja, derecha azul. Con orientación 6 (girar 90° a la derecha) queda de
        // 200 × 400, con el rojo arriba.
        byte[] girada = conOrientacion(jpegMitades(400, 200), 6, true);

        ImagenTicket.Imagen resultado = imagenes.preparar(girada);

        assertThat(resultado.ancho()).isEqualTo(200);
        assertThat(resultado.alto()).isEqualTo(400);
        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(resultado.jpeg()));
        assertThat(new Color(leida.getRGB(100, 50)).getRed()).isGreaterThan(200);
        assertThat(new Color(leida.getRGB(100, 350)).getBlue()).isGreaterThan(200);
    }

    @Test
    void elJpegNuevoNoLlevaElExifDeLaFotoOriginal() throws IOException {
        ImagenTicket.Imagen resultado = imagenes.preparar(conOrientacion(jpeg(300, 300), 3, false));

        assertThat(new String(resultado.jpeg(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void algoQueNoEsUnaImagenNoSePuedeUsar() {
        assertThatThrownBy(() -> imagenes.preparar("hola".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImagenInvalidaException.class)
                .extracting(e -> ((ImagenInvalidaException) e).getMotivo())
                .isEqualTo(ImagenInvalidaException.Motivo.FORMATO_NO_SOPORTADO);
    }

    @Test
    void leeLaOrientacionEnLosDosOrdenesDeBytes() throws IOException {
        assertThat(OrientacionExif.leer(conOrientacion(jpeg(10, 10), 6, true))).isEqualTo(6);
        assertThat(OrientacionExif.leer(conOrientacion(jpeg(10, 10), 8, false))).isEqualTo(8);
        assertThat(OrientacionExif.leer(jpeg(10, 10))).isEqualTo(OrientacionExif.NORMAL);
        assertThat(OrientacionExif.leer(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 99}))
                .isEqualTo(OrientacionExif.NORMAL);
    }

    // --- Imágenes de prueba ---

    private static byte[] jpeg(int ancho, int alto) throws IOException {
        BufferedImage imagen = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, ancho, alto);
        g.dispose();
        return codificar(imagen);
    }

    private static byte[] jpegMitades(int ancho, int alto) throws IOException {
        BufferedImage imagen = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, ancho / 2, alto);
        g.setColor(Color.BLUE);
        g.fillRect(ancho / 2, 0, ancho - ancho / 2, alto);
        g.dispose();
        return codificar(imagen);
    }

    private static byte[] codificar(BufferedImage imagen) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(imagen, "jpeg", bytes);
        return bytes.toByteArray();
    }

    /** Agrega un segmento APP1 con EXIF (solo el tag de orientación) después del inicio del JPEG. */
    private static byte[] conOrientacion(byte[] jpeg, int orientacion, boolean intel) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        if (intel) {
            escribir(tiff, 'I', 'I', 0x2A, 0x00, 8, 0, 0, 0); // encabezado, IFD0 en el byte 8
            escribir(tiff, 1, 0); // una entrada
            escribir(tiff, 0x12, 0x01, 3, 0, 1, 0, 0, 0, orientacion, 0, 0, 0); // 0x0112, SHORT, 1 valor
        } else {
            escribir(tiff, 'M', 'M', 0x00, 0x2A, 0, 0, 0, 8);
            escribir(tiff, 0, 1);
            escribir(tiff, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientacion, 0, 0);
        }
        escribir(tiff, 0, 0, 0, 0); // no hay más IFD
        byte[] datos = tiff.toByteArray();
        int largo = 2 + 6 + datos.length;

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        salida.write(jpeg, 0, 2); // FF D8
        escribir(salida, 0xFF, 0xE1, largo >> 8, largo & 0xFF, 'E', 'x', 'i', 'f', 0, 0);
        salida.write(datos, 0, datos.length);
        salida.write(jpeg, 2, jpeg.length - 2);
        return salida.toByteArray();
    }

    private static void escribir(ByteArrayOutputStream out, int... bytes) {
        for (int b : bytes) {
            out.write(b);
        }
    }
}
