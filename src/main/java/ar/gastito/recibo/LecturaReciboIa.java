package ar.gastito.recibo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que devuelve la IA al leer un recibo de sueldo (el schema JSON se genera desde este record). Todo viene como
 * texto y lo controla {@link ValidadorRecibo}: la IA puede leer mal. A propósito solo hay campos para lo que se
 * guarda: el CUIL, el CBU, el legajo y el domicilio no tienen dónde ir.
 */
public record LecturaReciboIa(
        @JsonPropertyDescription("Qué es el documento: TICKET (un ticket o factura de compra), RECIBO_SUELDO "
                + "(un recibo de sueldo o de haberes) u OTRO (cualquier otra cosa).")
        String tipoDocumento,

        @JsonPropertyDescription("Vacío si es un recibo y se lee bien. \"borroso\" si no se pueden leer los "
                + "conceptos o los importes; \"cortado\" si falta una parte (por ejemplo, los totales).")
        String problema,

        @JsonPropertyDescription("El empleador (razón social) con mayúsculas normales y sin S.A. ni S.R.L. "
                + "Ejemplo: Acme. Vacío si no figura.")
        String empleador,

        @JsonPropertyDescription("El mes y el año que liquida el recibo, en formato AAAA-MM. Ejemplo: 2026-10. "
                + "Si es un aguinaldo, el mes de la quincena o semestre que figura (junio o diciembre).")
        String periodo,

        @JsonPropertyDescription("La fecha de pago o de depósito copiada como está impresa (dd/mm/aaaa). "
                + "Vacío si no figura.")
        String fechaPago,

        @JsonPropertyDescription("sueldo_mensual, aguinaldo (SAC, sueldo anual complementario), vacaciones o "
                + "liquidacion_final")
        String tipo,

        @JsonPropertyDescription("Cada concepto del recibo, en el orden impreso, sin los totales.")
        List<Concepto> conceptos,

        @JsonPropertyDescription("El total bruto impreso (remuneraciones y conceptos no remunerativos), en pesos. "
                + "Vacío si no figura; no lo sumes vos.")
        String bruto,

        @JsonPropertyDescription("El total de descuentos o deducciones impreso, en pesos. Vacío si no figura; no "
                + "lo sumes vos.")
        String totalDescuentos,

        @JsonPropertyDescription("El neto a cobrar impreso (\"neto\", \"total neto\", \"importe neto\"), en "
                + "pesos. Vacío si no se lee.")
        String neto) {

    public record Concepto(
            @JsonPropertyDescription("Cómo figura el concepto, legible y sin código. Ejemplo: Jubilación 11%")
            String descripcion,

            @JsonPropertyDescription("REMUNERATIVO (básico, antigüedad, presentismo, horas extra, adicionales que "
                    + "se cobran como sueldo), NO_REMUNERATIVO (sumas no remunerativas) o DESCUENTO (jubilación, "
                    + "ley 19.032 / PAMI, obra social, sindicato, impuesto a las ganancias, cuotas)")
            String grupo,

            @JsonPropertyDescription("El importe del renglón en pesos, sin el signo menos aunque sea un descuento")
            String importe) {
    }
}
