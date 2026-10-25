package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.ItemTicket;

/**
 * Un ticket real de Día (22/09/2026), con la lectura esperada en src/test/resources/tickets/. La foto no está en
 * el repo: se prueba cómo queda lo que debería devolver la IA después de pasar por la validación en Java.
 */
class TicketRealDiaTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 25, 10, 0);

    private final ValidadorTicket validador = new ValidadorTicket(new ResolvedorFechas());

    @Test
    void elTicketDeDiaQuedaConElTotalImpresoYCadaDescuentoDebajoDeSuProducto() throws IOException {
        JsonNode esperado = esperado();

        ValidadorTicket.Resultado r = validador.validar(lectura(esperado), CatalogoDePrueba.crear(), AHORA);

        assertThat(r).isInstanceOf(ValidadorTicket.Valido.class);
        ValidadorTicket.Valido v = (ValidadorTicket.Valido) r;
        assertThat(v.movimiento().monto()).isEqualByComparingTo(esperado.get("total").decimalValue());
        assertThat(v.sumaItems()).isEqualByComparingTo("30666.40");
        assertThat(v.coincide()).isTrue();
        assertThat(v.movimiento().fechaHora()).isEqualTo(LocalDateTime.of(2026, 9, 22, 17, 44));
        assertThat(v.comercio()).isEqualTo("Día");

        List<ItemTicket> items = v.items();
        assertThat(items).hasSize(esperado.get("items").size());
        assertThat(items.stream().filter(i -> i.subtotal().signum() < 0)).hasSize(11);
        assertThat(items.get(0).descripcion()).isEqualTo("Galletitas cracker queso");
        assertThat(items.get(0).cantidad()).isEqualByComparingTo("2");
        assertThat(items.get(1).descripcion()).isEqualTo("20% ClubDía");
        assertThat(items.get(1).subtotal()).isEqualByComparingTo("-900");
        // Los productos suman el subtotal impreso y los descuentos, lo impreso como descuentos.
        assertThat(items.stream().map(ItemTicket::subtotal).filter(s -> s.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(esperado.get("subtotal").decimalValue());
        assertThat(items.stream().map(ItemTicket::subtotal).filter(s -> s.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(esperado.get("descuentos").decimalValue());
    }

    @Test
    void unaTarjetaSinAclararDebitoOCreditoQuedaSinMedioDePago() throws IOException {
        // Lo impreso es "TARJETA": la IA no tiene que elegir entre débito y crédito, y si devolviera eso tal cual,
        // no coincide con ningún medio del catálogo.
        ValidadorTicket.Valido v = (ValidadorTicket.Valido) validador.validar(lectura(esperado()),
                CatalogoDePrueba.crear(), AHORA);

        assertThat(v.movimiento().medioPago()).isNull();
    }

    /** Lo que debería devolver la IA para este ticket, armado desde el JSON esperado. */
    private static LecturaTicketIa lectura(JsonNode e) {
        List<LecturaTicketIa.Item> items = new ArrayList<>();
        for (JsonNode i : e.get("items")) {
            items.add(new LecturaTicketIa.Item(i.get("descripcion").asText(), i.get("cantidad").asText(),
                    i.get("precioUnitario").decimalValue().abs().toPlainString(),
                    i.get("subtotal").decimalValue().toPlainString()));
        }
        String fecha = e.get("fecha").asText(); // 2026-09-22 → como está impreso, 22/09/26
        String impresa = fecha.substring(8, 10) + "/" + fecha.substring(5, 7) + "/" + fecha.substring(2, 4);
        return new LecturaTicketIa(e.get("esTicket").asBoolean() ? "" : "no_es_ticket", e.get("comercio").asText(),
                impresa, e.get("hora").asText(), items, List.of(), e.get("total").decimalValue().toPlainString(),
                "Tarjeta", "", List.of("Supermercado"), "alta");
    }

    private static JsonNode esperado() throws IOException {
        try (InputStream in = TicketRealDiaTest.class.getResourceAsStream("/tickets/dia-2026-09-22-esperado.json")) {
            return new ObjectMapper().readTree(in);
        }
    }
}
