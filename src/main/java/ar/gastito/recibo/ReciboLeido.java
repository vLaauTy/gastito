package ar.gastito.recibo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Un recibo ya revisado por Java: los importes son números, el período es un mes real y los conceptos tienen
 * grupo. Los importes de los conceptos son positivos: el grupo dice si suman o restan.
 *
 * @param fechaPago  null si no se leyó
 * @param bruto      el impreso, o remunerativo + no remunerativo si no se leyó
 * @param descuentos el total impreso, o la suma de los conceptos de descuento si no se leyó
 * @param aviso      qué no cerró, o null si todo cerró
 */
public record ReciboLeido(String empleador, YearMonth periodo, LocalDate fechaPago, TipoRecibo tipo,
        List<Concepto> conceptos, BigDecimal bruto, BigDecimal descuentos, BigDecimal neto, String aviso) {

    public record Concepto(String descripcion, GrupoConcepto grupo, BigDecimal importe) {
    }
}
