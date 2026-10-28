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
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.TipoConceptoSueldo;

/**
 * Los recibos de sueldo de prueba de un empleado inventado. El del sueldo mensual sale del cálculo del mes (con lo
 * que coincide), salvo el que se pide con un porcentaje de descuento distinto; el aguinaldo es la mitad de la mejor
 * remuneración del semestre. Todo es inventado (la empresa, los importes): no sale de ningún recibo real.
 */
final class PlanRecibosPrueba {

    static final String EMPLEADOR = "Empresa de Prueba";
    /** El porcentaje de la ley 19.032 que descuenta el recibo "raro", en lugar del de la escala. */
    static final BigDecimal PORCENTAJE_RARO = new BigDecimal("2.5");
    static final String CONCEPTO_RARO = "Ley 19.032";

    private PlanRecibosPrueba() {
    }

    /**
     * El recibo del sueldo de un mes a partir de su cálculo: un renglón por concepto con importe, que se cobra el
     * 3 del mes siguiente.
     *
     * @param porcentajeRaro si la ley 19.032 se descuenta al 2,5% en lugar del de la escala
     */
    static ReciboLeido sueldo(CalculoSueldo calculo, boolean porcentajeRaro) {
        List<ReciboLeido.Concepto> conceptos = new ArrayList<>();
        BigDecimal remunerativo = calculo.getLineas().stream()
                .filter(l -> l.getTipo() == TipoConceptoSueldo.REMUNERATIVO_FIJO || l.getTipo() == TipoConceptoSueldo.HORAS)
                .map(CalculoSueldo.Linea::getImporte).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (CalculoSueldo.Linea l : calculo.getLineas()) {
            if (l.getImporte().signum() == 0) {
                continue;
            }
            GrupoConcepto grupo = switch (l.getTipo()) {
                case REMUNERATIVO_FIJO, HORAS -> GrupoConcepto.REMUNERATIVO;
                case NO_REMUNERATIVO -> GrupoConcepto.NO_REMUNERATIVO;
                case DESCUENTO_PORCENTAJE -> GrupoConcepto.DESCUENTO;
            };
            BigDecimal importe = porcentajeRaro && CONCEPTO_RARO.equals(l.getNombre()) ? porcentaje(remunerativo,
                    PORCENTAJE_RARO) : l.getImporte();
            conceptos.add(concepto(l.getNombre(), grupo, importe));
        }
        return armar(calculo.getPeriodo(), TipoRecibo.SUELDO_MENSUAL, conceptos,
                calculo.getPeriodo().plusMonths(1).atDay(3));
    }

    /** El aguinaldo del semestre: la mitad de la mejor remuneración, con los descuentos de ley. */
    static ReciboLeido aguinaldo(YearMonth mes, BigDecimal mejorRemuneracion) {
        BigDecimal sac = porcentaje(mejorRemuneracion, "50");
        List<ReciboLeido.Concepto> conceptos = List.of(
                concepto("Sueldo anual complementario", GrupoConcepto.REMUNERATIVO, sac),
                concepto("Jubilación", GrupoConcepto.DESCUENTO, porcentaje(sac, "11")),
                concepto("Ley 19.032", GrupoConcepto.DESCUENTO, porcentaje(sac, "3")),
                concepto("Obra social", GrupoConcepto.DESCUENTO, porcentaje(sac, "3")));
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

    private static ReciboLeido.Concepto concepto(String descripcion, GrupoConcepto grupo, BigDecimal importe) {
        return new ReciboLeido.Concepto(descripcion, grupo, importe);
    }

    private static BigDecimal porcentaje(BigDecimal base, BigDecimal porcentaje) {
        return base.multiply(porcentaje).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal porcentaje(BigDecimal base, String porcentaje) {
        return porcentaje(base, new BigDecimal(porcentaje));
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
