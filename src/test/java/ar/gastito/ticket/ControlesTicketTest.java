package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.ticket.ControlesTicket.Control;
import ar.gastito.ticket.ControlesTicket.Diferencia;

class ControlesTicketTest {

    /** El ticket de Día: 11 productos, cada uno con su 20% ClubDía. */
    static final List<BigDecimal> DIA = montos("4500", "-900", "5460", "-1092", "2760", "-552", "3490", "-698", "4488",
            "-897.60", "2250", "-450", "5280", "-1056", "4850", "-970", "2940", "-588", "225", "-45", "2090", "-418");

    @Test
    void conElSubtotalImpresoLasTresCuentasDan() {
        assertThat(ControlesTicket.revisar(DIA, pesos("38333"), pesos("-7666.60"), pesos("30666.40"))).isEmpty();
    }

    @Test
    void unProductoMalLeidoRompeElControlDeProductosPeroNoElDelTotal() {
        List<BigDecimal> malLeido = new ArrayList<>(DIA);
        malLeido.set(0, pesos("4300")); // el arrugado tapa un dígito: 4300 en vez de 4500

        assertThat(ControlesTicket.revisar(malLeido, pesos("38333"), pesos("-7666.60"), pesos("30666.40")))
                .containsExactly(new Diferencia(Control.PRODUCTOS_Y_SUBTOTAL, pesos("38133"), pesos("38333")));
    }

    @Test
    void unDescuentoQueFaltaRompeElControlDeDescuentos() {
        List<BigDecimal> sinUnDescuento = new ArrayList<>(DIA);
        sinUnDescuento.remove(pesos("-1092"));

        assertThat(ControlesTicket.revisar(sinUnDescuento, pesos("38333"), pesos("-7666.60"), pesos("30666.40")))
                .extracting(Diferencia::control).containsExactly(Control.DESCUENTOS);
    }

    @Test
    void unTotalMalLeidoRompeElControlDelTotal() {
        assertThat(ControlesTicket.revisar(DIA, pesos("38333"), pesos("-7666.60"), pesos("36666.40")))
                .containsExactly(new Diferencia(Control.SUBTOTAL_Y_TOTAL, pesos("30666.40"), pesos("36666.40")));
    }

    @Test
    void sinDescuentosImpresosElTotalSeControlaConLosDescuentosDeLosRenglones() {
        assertThat(ControlesTicket.revisar(DIA, pesos("38333"), null, pesos("30666.40"))).isEmpty();
        assertThat(ControlesTicket.revisar(DIA, pesos("38333"), null, pesos("31000")))
                .extracting(Diferencia::control).containsExactly(Control.SUBTOTAL_Y_TOTAL);
    }

    @Test
    void sinSubtotalImpresoSoloSeComparanLosRenglonesConElTotal() {
        assertThat(ControlesTicket.revisar(DIA, null, null, pesos("30666.40"))).isEmpty();
        assertThat(ControlesTicket.revisar(DIA, null, null, pesos("30676.40"))).isEmpty(); // $10 de tolerancia
        assertThat(ControlesTicket.revisar(DIA, null, null, pesos("30676.41")))
                .containsExactly(new Diferencia(Control.PRODUCTOS_Y_TOTAL, pesos("30666.40"), pesos("30676.41")));
    }

    @Test
    void sinRenglonesSoloQuedaElSubtotalContraElTotal() {
        assertThat(ControlesTicket.revisar(List.of(), null, null, pesos("1500"))).isEmpty();
        assertThat(ControlesTicket.revisar(List.of(), pesos("1500"), pesos("-100"), pesos("1400"))).isEmpty();
        assertThat(ControlesTicket.revisar(List.of(), pesos("1500"), pesos("-100"), pesos("1500")))
                .extracting(Diferencia::control).containsExactly(Control.SUBTOTAL_Y_TOTAL);
    }

    private static BigDecimal pesos(String monto) {
        return new BigDecimal(monto).setScale(2);
    }

    private static List<BigDecimal> montos(String... montos) {
        return Arrays.stream(montos).map(ControlesTicketTest::pesos).toList();
    }
}
