package ar.gastito.prueba;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.ReciboLeido;
import ar.gastito.recibo.TipoRecibo;

/**
 * Los recibos de sueldo de prueba: seis meses seguidos de un empleado inventado, con un aumento a mitad de camino y
 * el aguinaldo del semestre. Todo es inventado (la empresa, los importes): no sale de ningún recibo real.
 */
final class PlanRecibosPrueba {

    static final String EMPLEADOR = "Empresa de Prueba";
    static final int MESES = 6;
    static final BigDecimal BASICO_INICIAL = new BigDecimal("900000");
    /** El aumento de las paritarias, desde el mes 4. */
    static final BigDecimal AUMENTO = new BigDecimal("1.15");
    static final BigDecimal NO_REMUNERATIVO_INICIAL = new BigDecimal("120000");
    static final BigDecimal NO_REMUNERATIVO_NUEVO = new BigDecimal("150000");

    private PlanRecibosPrueba() {
    }

    /** Los recibos de los {@code MESES} meses que terminan en {@code hasta}, del más viejo al más nuevo. */
    static List<ReciboLeido> armar(YearMonth hasta) {
        List<ReciboLeido> recibos = new ArrayList<>();
        YearMonth desde = hasta.minusMonths(MESES - 1L);
        BigDecimal mejorRemuneracion = BigDecimal.ZERO;
        for (int i = 0; i < MESES; i++) {
            YearMonth mes = desde.plusMonths(i);
            boolean conAumento = i >= 3;
            BigDecimal basico = conAumento ? BASICO_INICIAL.multiply(AUMENTO) : BASICO_INICIAL;
            ReciboLeido sueldo = sueldo(mes, basico.setScale(2, RoundingMode.HALF_UP),
                    conAumento ? NO_REMUNERATIVO_NUEVO : NO_REMUNERATIVO_INICIAL, conAumento);
            mejorRemuneracion = mejorRemuneracion.max(remunerativo(sueldo));
            recibos.add(sueldo);
            if (mes.getMonthValue() == 6 || mes.getMonthValue() == 12) {
                recibos.add(aguinaldo(mes, mejorRemuneracion));
            }
        }
        return recibos;
    }

    private static ReciboLeido sueldo(YearMonth mes, BigDecimal basico, BigDecimal noRemunerativo, boolean ganancias) {
        BigDecimal antiguedad = porcentaje(basico, "5");
        BigDecimal presentismo = porcentaje(basico.add(antiguedad), "8.33");
        BigDecimal remunerativo = basico.add(antiguedad).add(presentismo);

        List<ReciboLeido.Concepto> conceptos = new ArrayList<>();
        conceptos.add(concepto("Sueldo básico", GrupoConcepto.REMUNERATIVO, basico));
        conceptos.add(concepto("Antigüedad 5%", GrupoConcepto.REMUNERATIVO, antiguedad));
        conceptos.add(concepto("Presentismo", GrupoConcepto.REMUNERATIVO, presentismo));
        conceptos.add(concepto("Suma no remunerativa", GrupoConcepto.NO_REMUNERATIVO, noRemunerativo));
        conceptos.add(concepto("Jubilación 11%", GrupoConcepto.DESCUENTO, porcentaje(remunerativo, "11")));
        conceptos.add(concepto("Ley 19.032 3%", GrupoConcepto.DESCUENTO, porcentaje(remunerativo, "3")));
        conceptos.add(concepto("Obra social 3%", GrupoConcepto.DESCUENTO, porcentaje(remunerativo, "3")));
        conceptos.add(concepto("Cuota sindical 2%", GrupoConcepto.DESCUENTO, porcentaje(remunerativo, "2")));
        if (ganancias) {
            conceptos.add(concepto("Impuesto a las ganancias", GrupoConcepto.DESCUENTO,
                    porcentaje(remunerativo, "4")));
        }
        return armar(mes, TipoRecibo.SUELDO_MENSUAL, conceptos, mes.plusMonths(1).atDay(3));
    }

    /** El aguinaldo: la mitad de la mejor remuneración del semestre, con los descuentos de ley. */
    private static ReciboLeido aguinaldo(YearMonth mes, BigDecimal mejorRemuneracion) {
        BigDecimal sac = porcentaje(mejorRemuneracion, "50");
        List<ReciboLeido.Concepto> conceptos = List.of(
                concepto("Sueldo anual complementario", GrupoConcepto.REMUNERATIVO, sac),
                concepto("Jubilación 11%", GrupoConcepto.DESCUENTO, porcentaje(sac, "11")),
                concepto("Ley 19.032 3%", GrupoConcepto.DESCUENTO, porcentaje(sac, "3")),
                concepto("Obra social 3%", GrupoConcepto.DESCUENTO, porcentaje(sac, "3")));
        return armar(mes, TipoRecibo.AGUINALDO, conceptos, mes.atEndOfMonth());
    }

    private static ReciboLeido armar(YearMonth mes, TipoRecibo tipo, List<ReciboLeido.Concepto> conceptos,
            LocalDate fechaPago) {
        BigDecimal bruto = BigDecimal.ZERO;
        BigDecimal descuentos = BigDecimal.ZERO;
        for (ReciboLeido.Concepto c : conceptos) {
            if (c.grupo() == GrupoConcepto.DESCUENTO) {
                descuentos = descuentos.add(c.importe());
            } else {
                bruto = bruto.add(c.importe());
            }
        }
        return new ReciboLeido(EMPLEADOR, mes, fechaPago, tipo, List.copyOf(conceptos), bruto, descuentos,
                bruto.subtract(descuentos), null);
    }

    private static BigDecimal remunerativo(ReciboLeido recibo) {
        return recibo.conceptos().stream().filter(c -> c.grupo() == GrupoConcepto.REMUNERATIVO)
                .map(ReciboLeido.Concepto::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ReciboLeido.Concepto concepto(String descripcion, GrupoConcepto grupo, BigDecimal importe) {
        return new ReciboLeido.Concepto(descripcion, grupo, importe);
    }

    private static BigDecimal porcentaje(BigDecimal base, String porcentaje) {
        return base.multiply(new BigDecimal(porcentaje)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Un PDF con texto de un recibo de prueba, para poder abrirlo desde el dashboard ("Ver recibo"). */
    static byte[] pdf(ReciboLeido recibo) {
        List<String> lineas = new ArrayList<>();
        lineas.add("RECIBO DE HABERES (DATOS DE PRUEBA) - " + recibo.empleador());
        lineas.add("Periodo: " + recibo.periodo() + "   Tipo: " + recibo.tipo().getNombre());
        lineas.add("Fecha de pago: " + recibo.fechaPago());
        for (ReciboLeido.Concepto c : recibo.conceptos()) {
            lineas.add(c.grupo().getNombre() + " | " + c.descripcion() + " | " + c.importe().toPlainString());
        }
        lineas.add("Total bruto: " + recibo.bruto().toPlainString());
        lineas.add("Total descuentos: " + recibo.descuentos().toPlainString());
        lineas.add("NETO A COBRAR: " + recibo.neto().toPlainString());
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage();
            documento.addPage(pagina);
            try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                contenido.beginText();
                contenido.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                contenido.setLeading(14);
                contenido.newLineAtOffset(40, 760);
                for (String linea : lineas) {
                    // La fuente estándar no tiene tildes ni signos raros: se las saco.
                    contenido.showText(Normalizer.normalize(linea, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", ""));
                    contenido.newLine();
                }
                contenido.endText();
            }
            documento.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
