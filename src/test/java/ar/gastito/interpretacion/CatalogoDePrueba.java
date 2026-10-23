package ar.gastito.interpretacion;

import java.util.List;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;

/** Catálogo como el del seed de V1, para tests sin base. */
public final class CatalogoDePrueba {

    public static final Categoria KIOSCO = new Categoria(1L, "Kiosco", "kiosco,kiosko,quiosco,almacen", "Golosinas, bebidas, snacks, compras chicas al paso.", "🍬");
    public static final Categoria SUPER = new Categoria(2L, "Supermercado", "super,supermercado,chino,dia", "Comida y productos de almacén para la casa.", "🛒");
    public static final Categoria TRANSPORTE = new Categoria(7L, "Transporte / Moto", "nafta,combustible,moto,transporte", "Nafta, colectivo, SUBE, Uber.", "🏍️");
    public static final Categoria COMIDA = new Categoria(4L, "Comida afuera / delivery", "delivery,pedidosya,rappi,pizza", "Comida lista en restaurantes o pedida por app.", "🍔");
    public static final Categoria SALUD = new Categoria(10L, "Salud / farmacia", "farmacia,salud,medico,remedios", "Farmacia, médicos, análisis.", "💊");
    public static final Categoria OTROS = new Categoria(13L, "Otros", "otros,otro,varios", "Lo que no encaja en ninguna otra categoría.", "📦");

    public static final MedioPago EFECTIVO = efectivo(new MedioPago("Efectivo", "efectivo,cash"));
    public static final MedioPago DEBITO = new MedioPago("Débito", "debito");
    public static final MedioPago CREDITO = new MedioPago("Tarjeta de crédito", "tarjeta,credito,mastercard");
    public static final MedioPago QR = new MedioPago("QR", "qr");

    public static final Cuenta GALICIA = new Cuenta("Galicia", "galicia");
    public static final Cuenta MERCADO_PAGO = new Cuenta("Mercado Pago", "mercadopago,mp");

    public static Catalogo crear() {
        return new Catalogo(List.of(KIOSCO, SUPER, TRANSPORTE, COMIDA, SALUD, OTROS),
                List.of(EFECTIVO, DEBITO, CREDITO, QR),
                List.of(GALICIA, MERCADO_PAGO),
                Optional.empty());
    }

    private static MedioPago efectivo(MedioPago medio) {
        ReflectionTestUtils.setField(medio, "efectivo", true);
        return medio;
    }

    private CatalogoDePrueba() {
    }
}
