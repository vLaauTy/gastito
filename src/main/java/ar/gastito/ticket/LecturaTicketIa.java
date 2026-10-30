package ar.gastito.ticket;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que devuelve la IA al leer la foto de un ticket (el schema JSON se genera desde este record).
 * Todo viene como texto y lo controla {@link ValidadorTicket}: la IA puede leer mal.
 * A propósito solo hay campos para lo que se guarda: el CUIT, la cajera, la caja, el número de tarjeta o de
 * cliente (ClubDía) no tienen dónde ir.
 */
public record LecturaTicketIa(
        @JsonPropertyDescription("Vacío si la foto es un ticket o factura de compra y se lee bien. Si no: "
                + "no_es_ticket (la foto es otra cosa), borrosa (no se pueden leer los productos o los importes) "
                + "o cortada (falta una parte del ticket, por ejemplo el total). recibo_sueldo si la foto es un recibo de "
                + "sueldo (no una compra). escala_sueldo si es una tabla de valores salariales por mes (la hoja del "
                + "sindicato).")
        String problema,

        @JsonPropertyDescription("Nombre del comercio como lo conoce la gente, con mayúsculas normales. "
                + "Ejemplo: Día. Si dice \"a cuenta y orden de X\", es X, nunca el franquiciado. Si solo figura la "
                + "razón social de una empresa, esa, sin S.A. ni S.R.L.")
        String comercio,

        @JsonPropertyDescription("Número o nombre de la sucursal si figura, sin la palabra sucursal. Ejemplo: 5601. "
                + "Vacío si no figura.")
        String sucursal,

        @JsonPropertyDescription("Fecha de la compra copiada como está impresa, sin la hora: dd/mm/aa, dd/mm/aaaa "
                + "o dd-mm-aaaa. Ejemplos: 22/09/26, 22-09-2026. Vacío si no se lee.")
        String fecha,

        @JsonPropertyDescription("Hora de la compra en formato HH:mm. Vacío si no se lee.")
        String hora,

        @JsonPropertyDescription("Los productos y los descuentos, en el orden en que están impresos (un descuento "
                + "va justo debajo del producto al que se aplica). Sin las líneas de IVA, subtotal, total, pago, "
                + "vuelto ni ahorro informativo.")
        List<Item> items,

        @JsonPropertyDescription("El SUBTOTAL impreso (antes de los descuentos), en pesos. Vacío si no figura.")
        String subtotal,

        @JsonPropertyDescription("El total de descuentos impreso como una línea propia (por ejemplo \"Descuentos\"), "
                + "en pesos. Vacío si no figura esa línea; no lo sumes vos.")
        String descuentos,

        @JsonPropertyDescription("El TOTAL impreso, en pesos. Vacío si no se lee.")
        String total,

        @JsonPropertyDescription("La forma de pago copiada tal como está impresa, sin números de tarjeta. "
                + "Ejemplos: TARJETA, DEBITO VISA, EFECTIVO, MERCADO PAGO. Vacío si no figura.")
        String medioPagoImpreso,

        @JsonPropertyDescription("Exactamente uno de los nombres de la lista de medios de pago, o vacío si el "
                + "ticket y el texto del usuario no lo dicen con seguridad")
        String medioPago,

        @JsonPropertyDescription("Exactamente uno de los nombres de la lista de cuentas, o vacío")
        String cuenta,

        @JsonPropertyDescription("Entre 1 y 3 nombres de la lista de categorías, de la más probable a la menos "
                + "probable, para la compra entera")
        List<String> categorias,

        @JsonPropertyDescription("Qué tan segura es la primera categoría: alta, media o baja")
        String confianza,

        @JsonPropertyDescription("Las líneas que no se pudieron leer bien (ticket arrugado, doblado, tapado o "
                + "gastado), una por elemento, diciendo cuál es y qué falta. Ejemplo: \"Renglón 7, debajo de "
                + "Premezcla brownies: el importe está tapado por un pliegue\". Vacía si se lee todo.")
        List<String> lineasIlegibles) {

    public record Item(
            @JsonPropertyDescription("Qué es, legible, con mayúscula inicial y sin abreviaturas. "
                    + "Ejemplo: Galletitas cracker queso. En un descuento, cómo figura: 20% ClubDía.")
            String descripcion,

            @JsonPropertyDescription("La descripción copiada tal como está impresa, sin la alícuota de IVA entre "
                    + "paréntesis ni el importe. Ejemplo: GALLET CRACKER QSO")
            String descripcionOriginal,

            @JsonPropertyDescription("El código de barras del producto si está impreso (8 o 13 dígitos), solo "
                    + "dígitos. Vacío si no figura o en un descuento.")
            String codigoBarras,

            @JsonPropertyDescription("Cantidad o peso (2, 0,532). Vacío si no figura.")
            String cantidad,

            @JsonPropertyDescription("Precio por unidad o por kilo, en pesos. Vacío si no figura.")
            String precioUnitario,

            @JsonPropertyDescription("Lo que se pagó por ese renglón, en pesos. En un descuento, lo que resta.")
            String subtotal,

            @JsonPropertyDescription("true si el renglón es un descuento, promoción o bonificación")
            boolean esDescuento) {
    }
}
