package ar.gastito.interpretacion;

import java.util.List;
import java.util.Optional;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.Cuenta;
import ar.gastito.movimiento.MedioPago;

/** Catálogo como el del seed de V1, para tests sin base. */
final class CatalogoDePrueba {

    static final Categoria KIOSCO = new Categoria("Kiosco", "kiosco,kiosko,quiosco,almacen");
    static final Categoria SUPER = new Categoria("Supermercado", "super,supermercado,chino,dia");
    static final Categoria TRANSPORTE = new Categoria("Transporte / Moto", "nafta,combustible,moto,transporte");
    static final Categoria COMIDA = new Categoria("Comida afuera / delivery", "delivery,pedidosya,rappi,pizza");
    static final Categoria SALUD = new Categoria("Salud / farmacia", "farmacia,salud,medico,remedios");
    static final Categoria OTROS = new Categoria("Otros", "otros,otro,varios");

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
