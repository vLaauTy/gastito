package ar.gastito.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReconocedorTarjetaTest {

    static final Cuenta GALICIA = new Cuenta("Galicia", "galicia");
    static final Cuenta CUENTA_MP = new Cuenta("Mercado Pago", "mercadopago,mp");
    static final MedioPago CREDITO = conId(new MedioPago(MedioPago.TARJETA_DE_CREDITO, "tarjeta,credito"), 3L);
    static final MedioPago DEBITO = conId(new MedioPago("Débito", "debito"), 2L);
    static final TarjetaCredito GOLD = conId(new TarjetaCredito("MasterCard Gold", "Galicia", GALICIA, null, "M",
            "master,mastercard,gold,la de galicia,galicia"), 1L);
    static final TarjetaCredito MP = conId(new TarjetaCredito("Mercado Pago", "Mercado Pago", CUENTA_MP, null, "P",
            "La de Mercado Pago, tarjeta de mercado pago,mercado pago,MP"), 2L);
    static final Catalogo DOS = new Catalogo(List.of(), List.of(DEBITO, CREDITO), List.of(GALICIA, CUENTA_MP),
            List.of(GOLD, MP));

    static <T> T conId(T entidad, long id) {
        try {
            Field f = entidad.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entidad, id);
            return entidad;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReconocedorTarjeta.Mencion reconocer(String texto, MedioPago medio) {
        return ReconocedorTarjeta.reconocer(texto, medio, DOS);
    }

    @Test
    void losAliasSeGuardanSinMayusculasNiEspaciosDeMas() {
        assertThat(MP.alias()).containsExactly("la de mercado pago", "tarjeta de mercado pago", "mercado pago", "mp");
    }

    @Test
    void cadaTarjetaSeReconocePorSusAlias() {
        assertThat(reconocer("kiosco 1500 con la master", null)).isEqualTo(new ReconocedorTarjeta.Mencion(GOLD, false));
        assertThat(reconocer("super 20000 con la MasterCard", null).tarjeta()).isEqualTo(GOLD);
        assertThat(reconocer("nafta 30000 con la gold", null).tarjeta()).isEqualTo(GOLD);
        assertThat(reconocer("ropa 50000 con la de galicia", null).clara()).isTrue();
        assertThat(reconocer("remera 9000 con la de mercado pago", null))
                .isEqualTo(new ReconocedorTarjeta.Mencion(MP, false));
        assertThat(reconocer("cena 25000 con la tarjeta de Mercado Pago", null).clara()).isTrue();
    }

    @Test
    void sinNombrarNingunaNoHayTarjeta() {
        assertThat(reconocer("kiosco 1500 con crédito", CREDITO).tarjeta()).isNull();
        assertThat(reconocer("kiosco 1500 con tarjeta", null).tarjeta()).isNull();
    }

    @Test
    void conMercadoPagoSoloEsDudoso() {
        assertThat(reconocer("almuerzo 8000 con mercado pago", null)).isEqualTo(new ReconocedorTarjeta.Mencion(MP, true));
        assertThat(reconocer("almuerzo 8000 con mp", null).dudosa()).isTrue();
        assertThat(reconocer("pagué 5000 con galicia", null)).isEqualTo(new ReconocedorTarjeta.Mencion(GOLD, true));
    }

    @Test
    void conMercadoPagoYCreditoEsLaTarjeta() {
        assertThat(reconocer("almuerzo 8000 con crédito de mercado pago", null))
                .isEqualTo(new ReconocedorTarjeta.Mencion(MP, false));
        assertThat(reconocer("almuerzo 8000 con mercado pago", CREDITO)).isEqualTo(new ReconocedorTarjeta.Mencion(MP,
                false));
    }

    @Test
    void conMercadoPagoYUnQrOUnaTransferenciaEsLaCuenta() {
        assertThat(reconocer("almuerzo 8000 con qr de mercado pago", null).tarjeta()).isNull();
        assertThat(reconocer("le transferí 8000 por mercado pago", null).tarjeta()).isNull();
        assertThat(reconocer("super 8000 con débito de galicia", null).tarjeta()).isNull();
        assertThat(reconocer("super 8000 con la master", DEBITO).tarjeta()).isNull();
    }

    @Test
    void siNombraLasDosNoElige() {
        assertThat(reconocer("algo con la master o la de mercado pago", null).tarjeta()).isNull();
    }

    @Test
    void lasOpcionesDeMedioTienenUnaPorTarjetaConVariasActivas() {
        assertThat(MedioElegido.opciones(DOS)).extracting(MedioElegido.Opcion::nombre, MedioElegido.Opcion::valor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Débito", 2L),
                        org.assertj.core.groups.Tuple.tuple("MasterCard Gold", -1L),
                        org.assertj.core.groups.Tuple.tuple("Mercado Pago", -2L));
        assertThat(MedioElegido.de(-2, DOS)).contains(new MedioElegido(CREDITO, MP));
        assertThat(MedioElegido.de(3, DOS)).contains(new MedioElegido(CREDITO, null));
        Catalogo una = new Catalogo(List.of(), List.of(DEBITO, CREDITO), List.of(), List.of(GOLD));
        assertThat(MedioElegido.opciones(una)).extracting(MedioElegido.Opcion::valor).containsExactly(2L, 3L);
        assertThat(MedioElegido.de(3, una)).contains(new MedioElegido(CREDITO, GOLD));
    }
}
