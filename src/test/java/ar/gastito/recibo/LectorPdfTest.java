package ar.gastito.recibo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** Los PDF se arman acá mismo, con un recibo inventado: ningún archivo de verdad entra al repo. */
class LectorPdfTest {

    private final LectorPdf lector = new LectorPdf();

    /** Un PDF de una página con una línea de texto por elemento. */
    static byte[] pdf(String... lineas) throws IOException {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage();
            documento.addPage(pagina);
            try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                contenido.beginText();
                contenido.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                contenido.setLeading(14);
                contenido.newLineAtOffset(40, 760);
                for (String linea : lineas) {
                    contenido.showText(linea);
                    contenido.newLine();
                }
                contenido.endText();
            }
            documento.save(salida);
            return salida.toByteArray();
        }
    }

    static byte[] reciboInventado() throws IOException {
        return pdf("RECIBO DE HABERES - Empresa Ejemplo SA", "CUIT 30-12345678-9   Legajo: 4455",
                "CUIL 20-11111111-2   CBU 0070000000000012345678", "Periodo: 10/2026   Fecha de pago: 03/11/2026",
                "Sueldo basico                    1.000.000,00", "Antiguedad                         150.000,00",
                "Jubilacion 11%                     137.500,00", "Obra social 3%                      37.500,00",
                "Total remuneraciones             1.250.000,00", "Total descuentos                   212.500,00",
                "NETO A COBRAR                    1.037.500,00");
    }

    @Test
    void extraeElTextoDeUnPdfConTexto() throws IOException {
        String texto = lector.texto(reciboInventado()).orElseThrow();

        assertThat(texto).contains("Empresa Ejemplo SA").contains("Sueldo basico").contains("1.000.000,00")
                .contains("NETO A COBRAR").contains("1.037.500,00");
    }

    @Test
    void elTextoSaleSinCuilCuitCbuNiLegajo() throws IOException {
        String texto = lector.texto(reciboInventado()).orElseThrow();

        assertThat(texto).doesNotContain("30-12345678-9").doesNotContain("20-11111111-2")
                .doesNotContain("0070000000000012345678").doesNotContain("4455");
    }

    @Test
    void unPdfSinTextoEsUnEscaneoYSeDibuja() throws IOException {
        byte[] escaneado = pdf();

        assertThat(lector.texto(escaneado)).isEmpty();
        assertThat(lector.primeraPagina(escaneado)).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void unPdfConPocoTextoTambienSeLeeComoImagen() throws IOException {
        assertThat(lector.texto(pdf("Pagina 1 de 1"))).isEmpty();
    }

    @Test
    void unArchivoQueNoEsPdfSeRechaza() {
        assertThatThrownBy(() -> lector.texto("esto no es un pdf".getBytes()))
                .isInstanceOf(PdfInvalidoException.class);
        assertThatThrownBy(() -> lector.primeraPagina(new byte[0])).isInstanceOf(PdfInvalidoException.class);
    }
}
