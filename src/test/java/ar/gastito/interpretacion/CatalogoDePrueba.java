package ar.gastito.interpretacion;

import java.util.List;
import java.util.Optional;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;

/** Catálogo como el del seed de V1, para tests sin base. */
final class CatalogoDePrueba {

    static final Categoria KIOSCO = new Categoria(1L, "Kiosco", "kiosco,kiosko,quiosco,almacen", "Golosinas, bebidas, snacks, compras chicas al paso.", "🍬");
    static final Categoria SUPER = new Categoria(2L, "Supermercado", "super,supermercado,chino,dia", "Comida y productos de almacén para la casa.", "🛒");
    static final Categoria TRANSPORTE = new Categoria(7L, "Transporte / Moto", "nafta,combustible,moto,transporte", "Nafta, colectivo, SUBE, Uber.", "🏍️");
    static final Categoria COMIDA = new Categoria(4L, "Comida afuera / delivery", "delivery,pedidosya,rappi,pizza", "Comida lista en restaurantes o pedida por app.", "🍔");
    static final Categoria SALUD = new Categoria(10L, "Salud / farmacia", "farmacia,salud,medico,remedios", "Farmacia, médicos, análisis.", "💊");
    static final Categoria OTROS = new Categoria(13L, "Otros", "otros,otro,varios", "Lo que no encaja en ninguna otra categoría.", "📦");

    static final MedioPago EFECTIVO = new MedioPago("Efectivo", "efectivo,cash");
    static final MedioPago DEBITO = new MedioPago("Débito", "debito");
    static final MedioPago CREDITO = new MedioPago("Tarjeta de crédito", "tarjeta,credito,mastercard");
    static final MedioPago QR = new MedioPago("QR", "qr");

    static final Cuenta GALICIA = new Cuenta("Galicia", "galicia");
    static final Cuenta MERCADO_PAGO = new Cuenta("Mercado Pago", "mercadopago,mp");

    static Catalogo crear() {
        return new Catalogo(List.of(KIOSCO, SUPER, TRANSPORTE, COMIDA, SALUD, OTROS),
                List.of(EFECTIVO, DEBITO, CREDITO, QR),
                List.of(GALICIA, MERCADO_PAGO),
                Optional.empty());
    }

    private CatalogoDePrueba() {
    }
}
