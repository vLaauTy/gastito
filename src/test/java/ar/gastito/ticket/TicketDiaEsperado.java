package ar.gastito.ticket;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * La lectura esperada del ticket real de Día (22/09/2026), en src/test/resources/tickets/. La foto no está en
 * el repo: el JSON lo armó el usuario a mano, sin el CUIT, la cajera ni el número de ClubDía.
 */
final class TicketDiaEsperado {

    static final String RECURSO = "/tickets/dia-2026-09-22-esperado.json";

    /** Un renglón como figura en el JSON. */
    record Renglon(String descripcion, String codigoBarras, BigDecimal cantidad, BigDecimal precioUnitario,
            BigDecimal subtotal, boolean esDescuento) {
    }

    final JsonNode json;
    final String comercio;
    final String sucursal;
    final LocalDateTime fechaHora;
    final List<Renglon> renglones;
    final BigDecimal subtotal;
    final BigDecimal descuentos;
    final BigDecimal total;

    private TicketDiaEsperado(JsonNode json) {
        this.json = json;
        this.comercio = json.get("comercio").asText();
        this.sucursal = json.get("sucursal").asText();
        this.fechaHora = LocalDateTime.parse(json.get("fecha").asText() + "T" + json.get("hora").asText());
        List<Renglon> lista = new ArrayList<>();
        for (JsonNode i : json.get("items")) {
            lista.add(new Renglon(i.get("descripcion").asText(),
                    i.hasNonNull("codigoBarras") ? i.get("codigoBarras").asText() : null,
                    i.get("cantidad").decimalValue(), i.get("precioUnitario").decimalValue(),
                    i.get("subtotal").decimalValue(), i.path("esDescuento").asBoolean(false)));
        }
        this.renglones = List.copyOf(lista);
        this.subtotal = json.get("subtotal").decimalValue();
        this.descuentos = json.get("descuentos").decimalValue();
        this.total = json.get("total").decimalValue();
    }

    static TicketDiaEsperado cargar() {
        try (InputStream in = TicketDiaEsperado.class.getResourceAsStream(RECURSO)) {
            return new TicketDiaEsperado(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    long productos() {
        return renglones.stream().filter(r -> !r.esDescuento()).count();
    }

    /**
     * Lo que devolvería la IA si lee bien la foto, con los números como están impresos: los importes con coma
     * decimal y el TOTAL con punto. El medio de pago dice solo "TARJETA", así que va vacío.
     */
    LecturaTicketIa comoLaLeeriaLaIa() {
        List<LecturaTicketIa.Item> items = renglones.stream()
                .map(r -> new LecturaTicketIa.Item(r.descripcion(), "", r.codigoBarras() == null ? "" : r.codigoBarras(),
                        r.cantidad().toPlainString(), conComa(r.precioUnitario().abs()), conComa(r.subtotal()),
                        r.esDescuento()))
                .toList();
        String fecha = json.get("fecha").asText(); // 2026-09-22 → impresa como 22/09/26
        String impresa = fecha.substring(8, 10) + "/" + fecha.substring(5, 7) + "/" + fecha.substring(2, 4);
        return new LecturaTicketIa("", comercio, sucursal, impresa, json.get("hora").asText(), items,
                conComa(subtotal), conComa(descuentos), total.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                "", "", List.of("Supermercado"), "alta", List.of());
    }

    /** "4.500,00", "-897,60". */
    static String conComa(BigDecimal monto) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", simbolos).format(monto);
    }
}
