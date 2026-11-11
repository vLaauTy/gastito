package ar.gastito.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DetallePagoTest {

    @Test
    void reconoceCadaFormaDeContado() {
        assertThat(DetallePago.reconocer("pan 3700 con débito")).isEqualTo("Débito");
        assertThat(DetallePago.reconocer("le transferí 5000 al plomero")).isEqualTo("Transferencia");
        assertThat(DetallePago.reconocer("nafta 20 mil por transferencia")).isEqualTo("Transferencia");
        assertThat(DetallePago.reconocer("kiosco 1500 en efectivo")).isEqualTo("Efectivo");
        assertThat(DetallePago.reconocer("café 2500 con QR")).isEqualTo("QR");
        assertThat(DetallePago.reconocer("almuerzo 8000 con mercado pago")).isEqualTo("Mercado Pago");
        assertThat(DetallePago.reconocer("almuerzo 8000 con mp")).isEqualTo("Mercado Pago");
        assertThat(DetallePago.reconocer("ropa 30000 con la tarjeta de débito")).isEqualTo("Débito");
        assertThat(DetallePago.reconocer("DEBITO VISA")).isEqualTo("Débito");
    }

    @Test
    void conCreditoOSinDecirNadaNoHayDetalle() {
        assertThat(DetallePago.reconocer("remera 9000 con la tarjeta de mercado pago")).isNull();
        assertThat(DetallePago.reconocer("zapatillas con crédito")).isNull();
        assertThat(DetallePago.reconocer("pan 3700")).isNull();
        assertThat(DetallePago.reconocer(null)).isNull();
    }

    @Test
    void limpiarSacaEspaciosYVacioEsNull() {
        assertThat(DetallePago.limpiar("  Débito   Galicia ")).isEqualTo("Débito Galicia");
        assertThat(DetallePago.limpiar("   ")).isNull();
        assertThat(DetallePago.limpiar("x".repeat(60))).hasSize(DetallePago.LARGO_MAXIMO);
    }
}
