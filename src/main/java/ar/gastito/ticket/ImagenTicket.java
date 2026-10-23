package ar.gastito.ticket;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;

/**
 * Prepara la foto de un ticket para la IA: la endereza según el EXIF, la achica a
 * gastito.ticket.lado-maximo en el lado más largo (conservando la proporción) y la pasa a JPEG.
 * Al volver a codificarla se pierden los metadatos de la foto original, incluida la ubicación.
 */
@Component
public class ImagenTicket {

    static final float CALIDAD_JPEG = 0.85f;
    /** Más píxeles que esto no se abren: ninguna foto de celular llega, y protege la memoria. */
    static final long PIXELES_MAXIMOS = 50_000_000L;

    private final int ladoMaximo;

    @Autowired
    public ImagenTicket(GastitoProperties properties) {
        this(properties.ticket().ladoMaximo());
    }

    ImagenTicket(int ladoMaximo) {
        this.ladoMaximo = ladoMaximo;
    }

    /** La imagen lista para mandar y guardar. */
    public record Imagen(byte[] jpeg, int ancho, int alto) {
    }

    /** @throws ImagenInvalidaException si no es una imagen que se pueda abrir, o es enorme */
    public Imagen preparar(byte[] original) {
        BufferedImage imagen = leer(original);
        BufferedImage achicada = achicar(imagen);
        BufferedImage derecha = enderezar(achicada, OrientacionExif.leer(original));
        return new Imagen(jpeg(derecha), derecha.getWidth(), derecha.getHeight());
    }

    private static BufferedImage leer(byte[] original) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> lectores = in == null ? null : ImageIO.getImageReaders(in);
            if (lectores == null || !lectores.hasNext()) {
                throw new ImagenInvalidaException(ImagenInvalidaException.Motivo.FORMATO_NO_SOPORTADO,
                        "Formato de imagen no soportado");
            }
            ImageReader lector = lectores.next();
            try {
                lector.setInput(in, true, true);
                long pixeles = (long) lector.getWidth(0) * lector.getHeight(0);
                if (pixeles > PIXELES_MAXIMOS) {
                    throw new ImagenInvalidaException(ImagenInvalidaException.Motivo.DEMASIADO_GRANDE,
                            "La imagen tiene " + pixeles + " píxeles");
                }
                return lector.read(0);
            } finally {
                lector.dispose();
            }
        } catch (IOException e) {
            throw new ImagenInvalidaException(ImagenInvalidaException.Motivo.FORMATO_NO_SOPORTADO,
                    "No se pudo abrir la imagen: " + e.getMessage());
        }
    }

    /**
     * Al lado más largo permitido, de a mitades (achicar mucho de una sola vez pierde las letras chicas), y siempre
     * en RGB sobre blanco: así una imagen con transparencia queda bien en JPEG.
     */
    BufferedImage achicar(BufferedImage imagen) {
        int ancho = imagen.getWidth();
        int alto = imagen.getHeight();
        double escala = Math.min(1.0, (double) ladoMaximo / Math.max(ancho, alto));
        int anchoFinal = Math.max(1, (int) Math.round(ancho * escala));
        int altoFinal = Math.max(1, (int) Math.round(alto * escala));

        BufferedImage actual = imagen;
        int w = ancho;
        int h = alto;
        do {
            w = Math.max(anchoFinal, w / 2);
            h = Math.max(altoFinal, h / 2);
            if (w < 2 * anchoFinal && h < 2 * altoFinal) {
                w = anchoFinal;
                h = altoFinal;
            }
            actual = dibujar(actual, w, h);
        } while (w != anchoFinal || h != altoFinal);
        return actual;
    }

    private static BufferedImage dibujar(BufferedImage origen, int ancho, int alto) {
        BufferedImage destino = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, ancho, alto);
            g.drawImage(origen, 0, 0, ancho, alto, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    /** Aplica la orientación EXIF (1 a 8): giros de 90° y espejados, píxel por píxel. */
    static BufferedImage enderezar(BufferedImage imagen, int orientacion) {
        if (orientacion <= OrientacionExif.NORMAL || orientacion > 8) {
            return imagen;
        }
        int w = imagen.getWidth();
        int h = imagen.getHeight();
        boolean gira = orientacion >= 5;
        BufferedImage destino = new BufferedImage(gira ? h : w, gira ? w : h, BufferedImage.TYPE_INT_RGB);
        int[] fila = new int[w];
        for (int y = 0; y < h; y++) {
            imagen.getRGB(0, y, w, 1, fila, 0, w);
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (orientacion) {
                    case 2 -> { dx = w - 1 - x; dy = y; }
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }
                    case 4 -> { dx = x; dy = h - 1 - y; }
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = h - 1 - y; dy = x; }
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }
                    default -> { dx = y; dy = w - 1 - x; } // 8
                }
                destino.setRGB(dx, dy, fila[x]);
            }
        }
        return destino;
    }

    private static byte[] jpeg(BufferedImage imagen) {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(CALIDAD_JPEG);
            escritor.setOutput(out);
            escritor.write(null, new IIOImage(imagen, null, null), parametros);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el JPEG", e);
        } finally {
            escritor.dispose();
        }
        return bytes.toByteArray();
    }
}
