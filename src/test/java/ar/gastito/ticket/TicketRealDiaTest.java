package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.ItemTicket;

/**
 * El ticket real de Día (22/09/2026): lo que debería devolver la IA, después de pasar por la validación en Java.
 * La foto no está en el repo; con la foto, ver {@link LecturaTicketRealIT}.
 */
class TicketRealDiaTest {

    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 25, 10, 0);

    private final ValidadorTicket validador = new ValidadorTicket(new ResolvedorFechas());
    private final TicketDiaEsperado dia = TicketDiaEsperado.cargar();

    private ValidadorTicket.Valido validar(LecturaTicketIa lectura) {
        ValidadorTicket.Resultado r = validador.validar(lectura, CatalogoDePrueba.crear(), AHORA);
        assertThat(r).isInstanceOf(ValidadorTicket.Valido.class);
        return (ValidadorTicket.Valido) r;
    }

    @Test
    void elTotalEsElImpresoYLasTresCuentasDan() {
        ValidadorTicket.Valido v = validar(dia.comoLaLeeriaLaIa());

        assertThat(v.movimiento().monto()).isEqualByComparingTo("30666.40");
        assertThat(v.ticket().subtotalImpreso()).isEqualByComparingTo("38333");
        assertThat(v.ticket().descuentosImpresos()).isEqualByComparingTo("-7666.60");
        assertThat(v.diferencias()).isEmpty();
        assertThat(v.sumaItems()).isEqualByComparingTo("30666.40");
    }

    @Test
    void cadaDescuentoQuedaDebajoDeSuProductoConLosDosFormatosDeNumero() {
        List<ItemTicket> items = validar(dia.comoLaLeeriaLaIa()).items();

        assertThat(items).hasSize(dia.renglones.size());
        for (int i = 0; i < items.size(); i++) {
            TicketDiaEsperado.Renglon esperado = dia.renglones.get(i);
            assertThat(items.get(i).descripcion()).as("renglón %d", i).isEqualTo(esperado.descripcion());
            assertThat(items.get(i).subtotal()).as("renglón %d", i).isEqualByComparingTo(esperado.subtotal());
            assertThat(items.get(i).subtotal().signum() < 0).as("renglón %d", i).isEqualTo(esperado.esDescuento());
        }
        assertThat(items.get(0).cantidad()).isEqualByComparingTo("2");
        assertThat(items.get(0).precioUnitario()).isEqualByComparingTo("2250");
    }

    @Test
    void losCodigosDeBarrasSonEanValidosYLaBolsaNoTiene() {
        List<ItemTicket> items = validar(dia.comoLaLeeriaLaIa()).items();

        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).codigoBarras()).as("renglón %d", i).isEqualTo(dia.renglones.get(i).codigoBarras());
        }
        assertThat(items.stream().filter(i -> i.codigoBarras() != null)).hasSize(10);
    }

    @Test
    void comercioSucursalYFecha() {
        ValidadorTicket.Valido v = validar(dia.comoLaLeeriaLaIa());

        assertThat(v.comercio()).isEqualTo("Día");
        assertThat(v.ticket().sucursal()).isEqualTo("5601");
        assertThat(v.movimiento().fechaHora()).isEqualTo(LocalDateTime.of(2026, 9, 22, 17, 44));
    }

    @Test
    void unaTarjetaSinAclararDebitoOCreditoQuedaSinMedioDePago() {
        // Lo impreso es "TARJETA". Con la foto real, la IA igual eligió crédito: Java lo anula.
        assertThat(validar(conMedio("TARJETA", "Tarjeta de crédito")).movimiento().medioPago()).isNull();
        // Y si devolviera "Tarjeta" tal cual, tampoco se toma como crédito por la palabra clave.
        assertThat(validar(conMedio("TARJETA", "Tarjeta")).movimiento().medioPago()).isNull();
    }

    @Test
    void conTextoDelUsuarioMandaLoQueEscribio() {
        // "con la de crédito" junto a la foto: la IA elige crédito y el TARJETA impreso no lo anula.
        ValidadorTicket.Resultado r = validador.validar(conMedio("TARJETA", "Tarjeta de crédito"),
                CatalogoDePrueba.crear(), AHORA, true);

        assertThat(((ValidadorTicket.Valido) r).movimiento().medioPago()).isEqualTo(CatalogoDePrueba.CREDITO);
    }

    @Test
    void unaTarjetaAclaradaSeRespeta() {
        assertThat(validar(conMedio("TARJETA DEBITO", "Débito")).movimiento().medioPago())
                .isEqualTo(CatalogoDePrueba.DEBITO);
        assertThat(validar(conMedio("VISA", "Tarjeta de crédito")).movimiento().medioPago())
                .isEqualTo(CatalogoDePrueba.CREDITO);
        assertThat(ValidadorTicket.tarjetaSinAclarar("Tarjeta de Débito")).isFalse();
        assertThat(ValidadorTicket.tarjetaSinAclarar("TARJETA MASTERCARD")).isFalse();
        assertThat(ValidadorTicket.tarjetaSinAclarar("tarjeta")).isTrue();
        assertThat(ValidadorTicket.tarjetaSinAclarar("EFECTIVO")).isFalse();
        assertThat(ValidadorTicket.tarjetaSinAclarar(null)).isFalse();
    }

    private LecturaTicketIa conMedio(String impreso, String medio) {
        LecturaTicketIa l = dia.comoLaLeeriaLaIa();
        return new LecturaTicketIa(l.problema(), l.comercio(), l.sucursal(), l.fecha(), l.hora(), l.items(),
                l.subtotal(), l.descuentos(), l.total(), impreso, medio, "", l.categorias(), l.confianza(), List.of());
    }

    @Test
    void siUnProductoSeLeeMalPorUnPliegueLaTarjetaVaASaberQueControlFallaYQueLinea() {
        LecturaTicketIa bien = dia.comoLaLeeriaLaIa();
        List<LecturaTicketIa.Item> items = new java.util.ArrayList<>(bien.items());
        LecturaTicketIa.Item galletitas = items.get(0);
        items.set(0, new LecturaTicketIa.Item(galletitas.descripcion(), "", galletitas.codigoBarras(), "2", "2.250,00",
                "4.300,00", false)); // el pliegue tapa un dígito
        LecturaTicketIa arrugado = new LecturaTicketIa("", bien.comercio(), bien.sucursal(), bien.fecha(), bien.hora(),
                items, bien.subtotal(), bien.descuentos(), bien.total(), bien.medioPagoImpreso(), "", "",
                bien.categorias(), bien.confianza(),
                List.of("Renglón 1, Galletitas cracker queso: el subtotal está sobre un pliegue"));

        ValidadorTicket.Valido v = validar(arrugado);

        assertThat(v.diferencias()).containsExactly(new ControlesTicket.Diferencia(
                ControlesTicket.Control.PRODUCTOS_Y_SUBTOTAL, new BigDecimal("38133.00"), new BigDecimal("38333.00")));
        assertThat(v.ticket().lineasIlegibles()).containsExactly(
                "Renglón 1, Galletitas cracker queso: el subtotal está sobre un pliegue");
        assertThat(v.movimiento().monto()).isEqualByComparingTo("30666.40");
    }
}
