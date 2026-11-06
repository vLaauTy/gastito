package ar.gastito.inversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import ar.gastito.catalogo.Cuenta;

/** Los pedidos de inversiones sin IA, con Cocos (posiciones CEDEAR SPY y FCI money market), Galicia y Mercado Pago. */
class ParserInversionTest {

    Cuenta cocos;
    Cuenta galicia;
    Cuenta mercadoPago;
    PosicionInversion spy;
    PosicionInversion fci;
    ParserInversion.Contexto ctx;

    private static <T> T conId(T entidad, long id) {
        ReflectionTestUtils.setField(entidad, "id", id);
        return entidad;
    }

    @BeforeEach
    void catalogo() {
        cocos = conId(Cuenta.deInversion("Cocos", null, 7), 10);
        galicia = conId(new Cuenta("Galicia", "galicia"), 1);
        mercadoPago = conId(new Cuenta("Mercado Pago", "mercadopago,mp"), 2);
        LocalDateTime ahora = LocalDateTime.of(2026, 11, 5, 11, 0);
        spy = conId(new PosicionInversion(cocos, "CEDEAR SPY", "spy", ahora), 100);
        fci = conId(new PosicionInversion(cocos, "FCI money market", "money market", ahora), 101);
        ctx = new ParserInversion.Contexto(List.of(cocos), List.of(spy, fci), List.of(galicia, mercadoPago));
    }

    private Optional<ParserInversion.Pedido> parsear(String texto) {
        return ParserInversion.parsear(texto, ctx);
    }

    @Test
    void saldoInicialConPosicionesYCuentaNueva() {
        ParserInversion.Contexto vacio = new ParserInversion.Contexto(List.of(), List.of(), List.of(galicia));

        ParserInversion.Pedido p = ParserInversion.parsear(
                "Tengo 850.000 invertidos en Cocos, 500.000 en CEDEAR SPY y 350.000 en un FCI", vacio).orElseThrow();

        assertThat(p).isInstanceOfSatisfying(ParserInversion.SaldoInicial.class, s -> {
            assertThat(s.cuenta()).isNull();
            assertThat(s.nombreCuenta()).isEqualTo("Cocos");
            assertThat(s.total()).isEqualByComparingTo("850000");
            assertThat(s.posiciones()).extracting(InversionService.SaldoPedido::posicion)
                    .containsExactly("CEDEAR SPY", "FCI");
        });
    }

    @Test
    void saldoInicialDeUnaCuentaExistente() {
        assertThat(parsear("tengo 900 mil invertidos en cocos")).get()
                .isInstanceOfSatisfying(ParserInversion.SaldoInicial.class, s -> {
                    assertThat(s.cuenta()).isSameAs(cocos);
                    assertThat(s.total()).isEqualByComparingTo("900000");
                    assertThat(s.posiciones()).isEmpty();
                });
        assertThat(parsear("tengo invertidos en Cocos 500.000 en SPY y 350.000 en el FCI")).get()
                .isInstanceOfSatisfying(ParserInversion.SaldoInicial.class, s -> {
                    assertThat(s.total()).isNull();
                    assertThat(s.posiciones()).extracting(InversionService.SaldoPedido::posicion)
                            .containsExactly("CEDEAR SPY", "FCI money market");
                });
    }

    @Test
    void ahorradoEnElBancoNoCreaUnaCuentaDeInversion() {
        assertThat(parsear("tengo 500.000 ahorrados en el banco")).isEmpty();
    }

    @Test
    void aporteDesdeUnaCuenta() {
        assertThat(parsear("pasé 200.000 de Galicia a Cocos")).get()
                .isInstanceOfSatisfying(ParserInversion.Aporte.class, a -> {
                    assertThat(a.cuenta()).isSameAs(cocos);
                    assertThat(a.desde()).isSameAs(galicia);
                    assertThat(a.monto()).isEqualByComparingTo("200000");
                    assertThat(a.posicion()).isNull();
                });
    }

    @Test
    void aporteSinOrigenYEnEfectivo() {
        assertThat(parsear("puse 200 mil en Cocos")).get()
                .isInstanceOfSatisfying(ParserInversion.Aporte.class, a -> assertThat(a.origenDicho()).isFalse());
        assertThat(parsear("metí 50 lucas en efectivo a Cocos")).get()
                .isInstanceOfSatisfying(ParserInversion.Aporte.class, a -> {
                    assertThat(a.efectivo()).isTrue();
                    assertThat(a.monto()).isEqualByComparingTo("50000");
                });
    }

    @Test
    void retiro() {
        assertThat(parsear("saqué 150.000 de Cocos a Galicia")).get()
                .isInstanceOfSatisfying(ParserInversion.Retiro.class, r -> {
                    assertThat(r.cuenta()).isSameAs(cocos);
                    assertThat(r.hacia()).isSameAs(galicia);
                    assertThat(r.monto()).isEqualByComparingTo("150000");
                });
        assertThat(parsear("retiré 20.000 de cocos")).get()
                .isInstanceOfSatisfying(ParserInversion.Retiro.class, r -> assertThat(r.destinoDicho()).isFalse());
        assertThat(parsear("vendí 30.000 de SPY y lo pasé a mp")).get()
                .isInstanceOfSatisfying(ParserInversion.Retiro.class, r -> {
                    assertThat(r.posicion()).isSameAs(spy);
                    assertThat(r.hacia()).isSameAs(mercadoPago);
                });
    }

    @Test
    void compraYVentaDentroDeLaCuenta() {
        assertThat(parsear("Compré 100.000 de CEDEAR SPY")).get()
                .isInstanceOfSatisfying(ParserInversion.Compra.class, c -> {
                    assertThat(c.cuenta()).isSameAs(cocos);
                    assertThat(c.posicion()).isSameAs(spy);
                    assertThat(c.monto()).isEqualByComparingTo("100000");
                });
        assertThat(parsear("compré 80.000 de CEDEAR QQQ en Cocos")).get()
                .isInstanceOfSatisfying(ParserInversion.Compra.class, c -> {
                    assertThat(c.posicion()).isNull();
                    assertThat(c.posicionNueva()).isEqualTo("CEDEAR QQQ");
                });
        assertThat(parsear("rescaté 40.000 del fci")).get()
                .isInstanceOfSatisfying(ParserInversion.Venta.class, v -> assertThat(v.posicion()).isSameAs(fci));
    }

    @Test
    void valuaciones() {
        assertThat(parsear("Cocos vale 870.000")).get()
                .isInstanceOfSatisfying(ParserInversion.Valuacion.class, v -> {
                    assertThat(v.cuenta()).isSameAs(cocos);
                    assertThat(v.posicion()).isNull();
                    assertThat(v.valor()).isEqualByComparingTo("870000");
                });
        assertThat(parsear("SPY está en 520.000")).get()
                .isInstanceOfSatisfying(ParserInversion.Valuacion.class, v -> {
                    assertThat(v.posicion()).isSameAs(spy);
                    assertThat(v.valor()).isEqualByComparingTo("520000");
                });
        assertThat(parsear("el money market quedó en 1,2 millones")).get()
                .isInstanceOfSatisfying(ParserInversion.Valuacion.class,
                        v -> assertThat(v.valor()).isEqualByComparingTo("1200000"));
    }

    @Test
    void reservaYLiberar() {
        assertThat(parsear("reservá 300.000 de Cocos para Bariloche")).get()
                .isInstanceOfSatisfying(ParserInversion.Reserva.class, r -> {
                    assertThat(r.cuenta()).isSameAs(cocos);
                    assertThat(r.meta()).isEqualTo("Bariloche");
                    assertThat(r.monto()).isEqualByComparingTo("300000");
                });
        assertThat(parsear("liberá la reserva de Bariloche")).get()
                .isInstanceOfSatisfying(ParserInversion.LiberarReserva.class,
                        l -> assertThat(l.meta()).isEqualTo("Bariloche"));
    }

    @Test
    void consulta() {
        assertThat(parsear("¿cuánto tengo invertido?")).get().isInstanceOf(ParserInversion.Consulta.class);
        assertThat(parsear("cómo van mis inversiones")).get().isInstanceOf(ParserInversion.Consulta.class);
        assertThat(parsear("cuánto vale cocos?")).get().isInstanceOf(ParserInversion.Consulta.class);
    }

    @Test
    void unGastoNoEsDeInversiones() {
        assertThat(parsear("kiosco 1500")).isEmpty();
        assertThat(parsear("pagué 3000 con mercado pago")).isEmpty();
        assertThat(parsear("¿cuánto gasté en kiosco?")).isEmpty();
        assertThat(parsear("tengo que pagar 5000 de luz")).isEmpty();
    }
}
