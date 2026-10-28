package ar.gastito.recibo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Saca el texto de un PDF (sin IA) y, si no tiene texto porque es un escaneo, dibuja su primera página. */
@Component
public class LectorPdf {

    /** Con menos letras y números que estos, el PDF es una imagen escaneada. */
    static final int MINIMO_CARACTERES = 80;
    static final int MAXIMO_PAGINAS = 10;
    private static final float DPI = 150;

    /**
     * El texto del PDF, sin CUIL, CBU ni legajo. Vacío si no tiene texto que sirva.
     *
     * @throws PdfInvalidoException si no se puede abrir
     */
    public Optional<String> texto(byte[] pdf) {
        try (PDDocument documento = abrir(pdf)) {
            PDFTextStripper lector = new PDFTextStripper();
            lector.setSortByPosition(true);
            String texto = lector.getText(documento);
            long utiles = texto.chars().filter(Character::isLetterOrDigit).count();
            return utiles < MINIMO_CARACTERES ? Optional.empty()
                    : Optional.of(DatosPersonales.limpiarVariasLineas(texto));
        } catch (IOException e) {
            throw new PdfInvalidoException("No se pudo leer el texto del PDF", e);
        }
    }

    /**
     * La primera página como PNG, para leerla con visión.
     *
     * @throws PdfInvalidoException si no se puede abrir
     */
    public byte[] primeraPagina(byte[] pdf) {
        try (PDDocument documento = abrir(pdf)) {
            BufferedImage imagen = new PDFRenderer(documento).renderImageWithDPI(0, DPI, ImageType.RGB);
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(imagen, "png", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new PdfInvalidoException("No se pudo dibujar la página del PDF", e);
        }
    }

    private static PDDocument abrir(byte[] pdf) {
        try {
            PDDocument documento = Loader.loadPDF(pdf);
            int paginas = documento.getNumberOfPages();
            if (paginas == 0 || paginas > MAXIMO_PAGINAS) {
                documento.close();
                throw new PdfInvalidoException("El PDF tiene " + paginas + " páginas", null);
            }
            return documento;
        } catch (IOException e) {
            throw new PdfInvalidoException("No es un PDF que se pueda abrir", e);
        }
    }
}
