package ar.gastito.ticket;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que devuelve la IA al leer la foto de un ticket (el schema JSON se genera desde este record).
 * Todo viene como texto y lo controla {@link ValidadorTicket}: la IA puede leer mal.
 * A propósito no hay campos para la tarjeta, el CUIT ni el cajero: no se guardan.
 */
public record LecturaTicketIa(
        @JsonPropertyDescription("Vacío si la foto es un ticket o factura de compra y se lee bien. Si no: "
                + "no_es_ticket (la foto es otra cosa), borrosa (no se pueden leer los productos o los importes) "
                + "o cortada (falta una parte del ticket, por ejemplo el total).")
        String problema,

        @JsonPropertyDescription("Nombre del comercio como lo conoce la gente, con mayúsculas normales. "
                + "Ejemplo: Supermercado Día. Si solo figura la razón social, esa, sin S.A. ni S.R.L.")
        String comercio,

        @JsonPropertyDescription("Fecha de la compra copiada como está impresa, en formato dd/mm/aa o dd/mm/aaaa. "
                + "Ejemplo: 18/10/26. Vacío si no se lee.")
        String fecha,

        @JsonPropertyDescription("Hora de la compra en formato HH:mm. Vacío si no se lee.")
        String hora,

        @JsonPropertyDescription("Los productos, en el orden del ticket. Sin las líneas de IVA, subtotal, total, "
                + "pago, vuelto ni descuentos.")
        List<Item> items,

        @JsonPropertyDescription("Promociones, 2x1, descuentos con tarjeta o bonificaciones que restan del total.")
        List<Descuento> descuentos,

        @JsonPropertyDescription("El TOTAL impreso, en pesos, solo dígitos y punto decimal, sin separador de "
                + "miles. Ejemplo: 8050 o 8050.50. Vacío si no se lee.")
        String total,

        @JsonPropertyDescription("Exactamente uno de los nombres de la lista de medios de pago, o vacío si el "
                + "ticket y el texto del usuario no lo dicen")
        String medioPago,

        @JsonPropertyDescription("Exactamente uno de los nombres de la lista de cuentas, o vacío")
        String cuenta,

        @JsonPropertyDescription("Entre 1 y 3 nombres de la lista de categorías, de la más probable a la menos "
                + "probable, para la compra entera")
        List<String> categorias,

        @JsonPropertyDescription("Qué tan segura es la primera categoría: alta, media o baja")
        String confianza) {

    public record Item(
            @JsonPropertyDescription("Qué es, corto y legible, con mayúscula inicial. Ejemplo: Leche 1 L. "
                    + "Sin códigos de barras ni números de artículo.")
            String descripcion,

            @JsonPropertyDescription("Cantidad o peso, solo dígitos y punto decimal (2, 0.532). Vacío si no figura.")
            String cantidad,

            @JsonPropertyDescription("Precio por unidad o por kilo, en pesos, solo dígitos y punto decimal. "
                    + "Vacío si no figura.")
            String precioUnitario,

            @JsonPropertyDescription("Lo que se pagó por ese renglón, en pesos, solo dígitos y punto decimal")
            String subtotal) {
    }

    public record Descuento(
            @JsonPropertyDescription("Cómo figura el descuento. Ejemplo: 2x1 Galletitas, 10% débito Galicia")
            String descripcion,

            @JsonPropertyDescription("Cuánto resta, en pesos, en positivo, solo dígitos y punto decimal")
            String monto) {
    }
}
