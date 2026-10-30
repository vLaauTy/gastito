package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.interpretacion.CatalogoDePrueba;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.ItemTicket;

class ValidadorTicketTest {

    /** Jueves 22/10/2026 16:00. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 22, 16, 0);

    private final ValidadorTicket validador = new ValidadorTicket(new ResolvedorFechas());

    // --- Total ---

    @Test
    void conTotalQueCoincideElMontoEsElTotalImpreso() {
        ValidadorTicket.Valido v = valido(ticket().build());

        assertThat(v.movimiento().monto()).isEqualByComparingTo("8050");
        assertThat(v.movimiento().monto().scale()).isEqualTo(2);
        assertThat(v.sumaItems()).isEqualByComparingTo("8050");
        assertThat(v.coincide()).isTrue();
        assertThat(v.items()).extracting(ItemTicket::descripcion)
                .containsExactly("Leche 1 L", "Yerba 500 g", "Galletitas");
        assertThat(v.movimiento().descripcion()).isEqualTo("Supermercado Día");
        assertThat(v.comercio()).isEqualTo("Supermercado Día");
        assertThat(v.movimiento().categoria()).isEqualTo(CatalogoDePrueba.SUPER);
        assertThat(v.movimiento().medioPago()).isEqualTo(CatalogoDePrueba.DEBITO);
    }

    @Test
    void conTotalQueNoCoincideElMontoSigueSiendoElTotalYSeAvisa() {
        ValidadorTicket.Valido v = valido(ticket().total("9000").build());

        assertThat(v.movimiento().monto()).isEqualByComparingTo("9000");
        assertThat(v.sumaItems()).isEqualByComparingTo("8050");
        assertThat(v.coincide()).isFalse();
    }

    @Test
    void unaDiferenciaDeHastaDiezPesosNoSeAvisa() {
        assertThat(valido(ticket().total("8060").build()).coincide()).isTrue();
        assertThat(valido(ticket().total("8060.01").build()).coincide()).isFalse();
        assertThat(valido(ticket().total("8040").build()).coincide()).isTrue();
    }

    @Test
    void sinProductosNoHayConQueCompararYSeTomaElTotal() {
        ValidadorTicket.Valido v = valido(ticket().items(List.of()).total("1500").build());

        assertThat(v.items()).isEmpty();
        assertThat(v.sumaItems()).isNull();
        assertThat(v.coincide()).isTrue();
        assertThat(v.movimiento().monto()).isEqualByComparingTo("1500");
    }

    @Test
    void sinTotalNoHayGasto() {
        assertThat(validador.validar(ticket().total("").build(), CatalogoDePrueba.crear(), AHORA))
                .isEqualTo(new ValidadorTicket.Ilegible(ValidadorTicket.Motivo.SIN_TOTAL));
        assertThat(validador.validar(ticket().total("0").build(), CatalogoDePrueba.crear(), AHORA))
                .isEqualTo(new ValidadorTicket.Ilegible(ValidadorTicket.Motivo.SIN_TOTAL));
        assertThat(validador.validar(ticket().total(null).build(), CatalogoDePrueba.crear(), AHORA))
                .isEqualTo(new ValidadorTicket.Ilegible(ValidadorTicket.Motivo.SIN_TOTAL));
    }

    @Test
    void cortadaSinTotalPideOtraFotoPeroCortadaConTotalSeUsa() {
        assertThat(validador.validar(ticket().problema("cortada").total("").build(), CatalogoDePrueba.crear(), AHORA))
                .isEqualTo(new ValidadorTicket.Ilegible(ValidadorTicket.Motivo.CORTADA));
        assertThat(valido(ticket().problema("cortada").build()).movimiento().monto()).isEqualByComparingTo("8050");
    }

    @Test
    void unReciboDeSueldoSeReconoceParaLeerloConElFlujoDeRecibos() {
        assertThat(validador.validar(ticket().problema("recibo_sueldo").build(), CatalogoDePrueba.crear(), AHORA))
                .isInstanceOf(ValidadorTicket.EsRecibo.class);
    }

    @Test
    void laHojaDeLaEscalaSeReconoceParaLeerlaConElFlujoDeEscalas() {
        assertThat(validador.validar(ticket().problema("escala_sueldo").build(), CatalogoDePrueba.crear(), AHORA))
                .isInstanceOf(ValidadorTicket.EsEscala.class);
    }

    @Test
    void unaFotoQueNoEsTicketOBorrosaNoEsGasto() {
        assertThat(validador.validar(ticket().problema("no_es_ticket").build(), CatalogoDePrueba.crear(), AHORA))
                .isInstanceOf(ValidadorTicket.NoEsTicket.class);
        assertThat(validador.validar(ticket().problema("borrosa").build(), CatalogoDePrueba.crear(), AHORA))
                .isEqualTo(new ValidadorTicket.Ilegible(ValidadorTicket.Motivo.BORROSA));
        assertThat(validador.validar(null, CatalogoDePrueba.crear(), AHORA))
                .isEqualTo(new ValidadorTicket.Ilegible(ValidadorTicket.Motivo.BORROSA));
    }

    // --- Productos y descuentos ---

    @Test
    void losDescuentosQuedanEnElOrdenImpresoEnNegativoYRestanDeLaSuma() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Leche 1 L", "2", "1450", "2900"), item("Galletitas", "", "", "1250"),
                        descuento("2x1 Galletitas", "250"), item("Pan", "", "", "500")))
                .total("4400")
                .build());

        assertThat(v.items()).extracting(ItemTicket::descripcion)
                .containsExactly("Leche 1 L", "Galletitas", "2x1 Galletitas", "Pan");
        assertThat(v.items().get(2).subtotal()).isEqualByComparingTo("-250");
        assertThat(v.sumaItems()).isEqualByComparingTo("4400");
        assertThat(v.coincide()).isTrue();
    }

    @Test
    void unDescuentoImpresoComoProductoConSignoMenosQuedaNegativo() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Galletitas", "", "", "1250"), item("Promo", "", "", "-250")))
                .total("1000")
                .build());

        assertThat(v.items().get(1).subtotal()).isEqualByComparingTo("-250");
        assertThat(v.coincide()).isTrue();
    }

    @Test
    void unDescuentoConSignoMenosNoSeDuplicaElSigno() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Galletitas", "", "", "1250"), descuento("Desc.", "-250")))
                .total("1000")
                .build());

        assertThat(v.items().get(1).subtotal()).isEqualByComparingTo("-250");
    }

    // --- Los tres controles ---

    @Test
    void conSubtotalYDescuentosImpresosLasTresCuentasDan() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Galletitas", "2", "2250", "4500"), descuento("20% ClubDía", "900")))
                .subtotal("4.500,00").descuentos("-900,00").total("3600.00")
                .build());

        assertThat(v.coincide()).isTrue();
        assertThat(v.ticket().subtotalImpreso()).isEqualByComparingTo("4500");
        assertThat(v.ticket().descuentosImpresos()).isEqualByComparingTo("-900");
    }

    @Test
    void unProductoMalLeidoQuedaComoDiferenciaYConLaLineaQueNoSeLeyo() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Galletitas", "", "", "4300"), descuento("20% ClubDía", "900")))
                .subtotal("4500").descuentos("900").total("3600")
                .lineasIlegibles(List.of("renglón 1, Galletitas: el importe está doblado"))
                .build());

        assertThat(v.coincide()).isFalse();
        assertThat(v.diferencias()).extracting(ControlesTicket.Diferencia::control)
                .containsExactly(ControlesTicket.Control.PRODUCTOS_Y_SUBTOTAL);
        assertThat(v.ticket().lineasIlegibles()).containsExactly("Renglón 1, Galletitas: el importe está doblado");
        assertThat(v.movimiento().monto()).isEqualByComparingTo("3600"); // el monto sigue siendo el total impreso
    }

    @Test
    void losDosFormatosDeNumeroEnElMismoTicket() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Premezcla brownies", "1", "5.460,00", "5.460,00"),
                        descuento("20% ClubDía", "1.092,00"), item("Pasta mac & cheese", "1", "4488,00", "4488,00"),
                        descuento("20% ClubDía", "897,60")))
                .subtotal("9.948,00").total("7958.40")
                .build());

        assertThat(v.items()).extracting(ItemTicket::subtotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("5460"), new BigDecimal("-1092"), new BigDecimal("4488"),
                        new BigDecimal("-897.60"));
        assertThat(v.movimiento().monto()).isEqualByComparingTo("7958.40");
        assertThat(v.coincide()).isTrue();
    }

    // --- Código de barras, descripción original y sucursal ---

    @Test
    void elCodigoDeBarrasSeGuardaSoloSiEsUnEanValido() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(
                        new LecturaTicketIa.Item("Galletitas cracker queso", "GALLET CRACKER QSO", "7792180145444",
                                "2", "2250", "4500", false),
                        new LecturaTicketIa.Item("Premezcla", "PREMEZ BROWNIE", "7790070432859", "", "", "5460", false),
                        new LecturaTicketIa.Item("20% ClubDía", "20% ClubDia", "7792180145444", "", "", "900", true)))
                .total("9060")
                .build());

        assertThat(v.items().get(0).codigoBarras()).isEqualTo("7792180145444");
        assertThat(v.items().get(0).descripcionOriginal()).isEqualTo("GALLET CRACKER QSO");
        assertThat(v.items().get(0).descripcion()).isEqualTo("Galletitas cracker queso");
        assertThat(v.items().get(1).codigoBarras()).isNull(); // dígito verificador mal leído
        assertThat(v.items().get(2).codigoBarras()).isNull(); // un descuento no tiene código
    }

    @Test
    void laAlicuotaDeIvaNoQuedaEnLasDescripciones() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(new LecturaTicketIa.Item("Galletitas cracker queso (21,00)", "GALLET CRACKER QSO (21,00)",
                        "", "2", "2250", "4500", false)))
                .total("4500")
                .build());

        assertThat(v.items().get(0).descripcion()).isEqualTo("Galletitas cracker queso");
        assertThat(v.items().get(0).descripcionOriginal()).isEqualTo("GALLET CRACKER QSO");
        assertThat(ValidadorTicket.sinAlicuota("LECHE (10,50) X")).isEqualTo("LECHE   X");
        assertThat(ValidadorTicket.sinAlicuota("PACK (6 U)")).isEqualTo("PACK (6 U)");
    }

    @Test
    void laSucursalQuedaComoTextoCorto() {
        assertThat(valido(ticket().sucursal(" Suc. 5601 ").build()).ticket().sucursal()).isEqualTo("Suc 5601");
        assertThat(valido(ticket().sucursal("5601").build()).ticket().sucursal()).isEqualTo("5601");
        assertThat(valido(ticket().sucursal("").build()).ticket().sucursal()).isNull();
    }

    @Test
    void sinSubtotalSeUsaCantidadPorPrecioYSinNadaSeDescarta() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Queso", "0,532", "5990", ""), item("Bolsa", "", "", ""), item("Pan", "", "", "0")))
                .total("3186.68")
                .build());

        assertThat(v.items()).singleElement().satisfies(i -> {
            assertThat(i.descripcion()).isEqualTo("Queso");
            assertThat(i.cantidad()).isEqualByComparingTo("0.532");
            assertThat(i.precioUnitario()).isEqualByComparingTo("5990");
            assertThat(i.subtotal()).isEqualByComparingTo("3186.68");
        });
    }

    @Test
    void losImportesALaArgentinaTambienSeEntienden() {
        ValidadorTicket.Valido v = valido(ticket()
                .items(List.of(item("Yerba", "", "", "3.900,00")))
                .total("3.900,00")
                .build());

        assertThat(v.movimiento().monto()).isEqualByComparingTo("3900");
        assertThat(v.items().get(0).subtotal()).isEqualByComparingTo("3900");
    }

    // --- Fecha ---

    @Test
    void siLaFechaDelTicketNoEsHoyUsaLaDelTicket() {
        assertThat(valido(ticket().build()).movimiento().fechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 18, 17, 20));
        assertThat(valido(ticket().fecha("18/10/2026").hora("").build()).movimiento().fechaHora())
                .isEqualTo(LocalDateTime.of(2026, 10, 18, 12, 0));
    }

    @Test
    void laFechaDeDiaConGuionesYSegundos() {
        // Como la imprime Día: 22-09-2026 17:44:28.
        assertThat(valido(ticket().fecha("22-09-2026").hora("17:44:28").build()).movimiento().fechaHora())
                .isEqualTo(LocalDateTime.of(2026, 9, 22, 17, 44));
        assertThat(valido(ticket().fecha("22-09-2026 17:44:28").hora("").build()).movimiento().fechaHora())
                .isEqualTo(LocalDateTime.of(2026, 9, 22, 17, 44));
        assertThat(valido(ticket().fecha("22/09/26").hora("17:44").build()).movimiento().fechaHora())
                .isEqualTo(LocalDateTime.of(2026, 9, 22, 17, 44));
    }

    @Test
    void conLaFechaDeHoyOSinFechaQuedaAhora() {
        assertThat(valido(ticket().fecha("22/10/26").hora("").build()).movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(valido(ticket().fecha("").hora("").build()).movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(valido(ticket().fecha("22/10/26").hora("10:15").build()).movimiento().fechaHora())
                .isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 15));
    }

    @Test
    void unaFechaFuturaOMuyViejaSeTomaComoMalLeida() {
        assertThat(valido(ticket().fecha("25/10/26").build()).movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(valido(ticket().fecha("22/10/26").hora("21:00").build()).movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(valido(ticket().fecha("18/10/16").build()).movimiento().fechaHora()).isEqualTo(AHORA);
        assertThat(valido(ticket().fecha("31/02/26").build()).movimiento().fechaHora()).isEqualTo(AHORA);
    }

    // --- Catálogo ---

    @Test
    void conConfianzaMediaPreguntaLaCategoria() {
        ValidadorTicket.Valido v = valido(ticket().categorias(List.of("Supermercado", "Kiosco")).confianza("media").build());

        assertThat(v.movimiento().categoria()).isNull();
        assertThat(v.movimiento().candidatas()).containsExactly(CatalogoDePrueba.SUPER, CatalogoDePrueba.KIOSCO);
        assertThat(v.confianza()).isEqualTo(Confianza.MEDIA);
    }

    @Test
    void siElTicketDiceSoloTarjetaElMedioQuedaVacioAunqueLaIaElija() {
        assertThat(valido(ticket().medioPagoImpreso("TARJETA").medioPago("Tarjeta de crédito").build())
                .movimiento().medioPago()).isNull();
        assertThat(valido(ticket().medioPagoImpreso("TARJETA DE DEBITO").medioPago("Débito").build())
                .movimiento().medioPago()).isEqualTo(CatalogoDePrueba.DEBITO);
    }

    @Test
    void conTarjetaDeCreditoNoQuedaLaCuenta() {
        ValidadorTicket.Valido v = valido(ticket().medioPago("Tarjeta de crédito").cuenta("Galicia").build());

        assertThat(v.movimiento().medioPago()).isEqualTo(CatalogoDePrueba.CREDITO);
        assertThat(v.movimiento().cuenta()).isNull();
    }

    @Test
    void unMedioDePagoQueNoExisteQuedaVacioYMercadoPagoEsQrConSuCuenta() {
        assertThat(valido(ticket().medioPago("Bitcoin").build()).movimiento().medioPago()).isNull();
        ValidadorTicket.Valido mp = valido(ticket().medioPago("QR").cuenta("Mercado Pago").build());
        assertThat(mp.movimiento().medioPago()).isEqualTo(CatalogoDePrueba.QR);
        assertThat(mp.movimiento().cuenta()).isEqualTo(CatalogoDePrueba.MERCADO_PAGO);
    }

    @Test
    void sinComercioLaDescripcionEsLaCategoria() {
        ValidadorTicket.Valido v = valido(ticket().comercio("").build());

        assertThat(v.comercio()).isNull();
        assertThat(v.movimiento().descripcion()).isEqualTo("Supermercado");
    }

    // --- Privacidad ---

    @Test
    void enLosTextosLibresBorraTarjetasYCuitPeroNoNumerosPorSuLargo() {
        ValidadorTicket.Valido v = valido(ticket()
                .comercio("Supermercado Día CUIT 30-68731043-4")
                .items(List.of(item("Leche 7790080001234", "", "", "2900"), item("Remera XXL", "", "", "3900"),
                        item("Pago **** 1234", "", "", "1250"), item("4509 9535 6623 3704", "", "", "1")))
                .lineasIlegibles(List.of("Línea del pago con tarjeta 4509-9535-6623-3704", "**** 1234"))
                .total("8051")
                .build());

        assertThat(v.comercio()).isEqualTo("Supermercado Día CUIT");
        assertThat(v.items()).extracting(ItemTicket::descripcion)
                .containsExactly("Leche 7790080001234", "Remera XXL", "Pago", "Producto");
        assertThat(v.ticket().lineasIlegibles()).containsExactly("Línea del pago con tarjeta");
    }

    @Test
    void limpiarDejaLoQueNoEsSensible() {
        assertThat(ValidadorTicket.limpiar("  agua 1.500 ml  ")).isEqualTo("agua 1.500 ml");
        assertThat(ValidadorTicket.limpiar("Cod 7792180145444")).isEqualTo("Cod 7792180145444");
        assertThat(ValidadorTicket.limpiar("CUIT 20123456789")).isEqualTo("CUIT");
        assertThat(ValidadorTicket.limpiar("XXXX-1234")).isNull();
        assertThat(ValidadorTicket.limpiar(null)).isNull();
    }

    // --- Ayudas ---

    private ValidadorTicket.Valido valido(LecturaTicketIa lectura) {
        ValidadorTicket.Resultado r = validador.validar(lectura, CatalogoDePrueba.crear(), AHORA);
        assertThat(r).isInstanceOf(ValidadorTicket.Valido.class);
        return (ValidadorTicket.Valido) r;
    }

    private static LecturaTicketIa.Item item(String descripcion, String cantidad, String precio, String subtotal) {
        return new LecturaTicketIa.Item(descripcion, "", "", cantidad, precio, subtotal, false);
    }

    private static LecturaTicketIa.Item descuento(String descripcion, String monto) {
        return new LecturaTicketIa.Item(descripcion, "", "", "", "", monto, true);
    }

    /** El ticket del pedido: Supermercado Día, sábado 18/10 17:20, tres productos, $8.050 con débito. */
    private static Constructor ticket() {
        return new Constructor();
    }

    private static final class Constructor {
        private String problema = "";
        private String comercio = "Supermercado Día";
        private String fecha = "18/10/26";
        private String hora = "17:20";
        private List<LecturaTicketIa.Item> items = List.of(
                item("Leche 1 L", "2", "1450", "2900"),
                item("Yerba 500 g", "", "", "3900"),
                item("Galletitas", "", "", "1250"));
        private String sucursal = "";
        private String subtotal = "";
        private String descuentos = "";
        private List<String> lineasIlegibles = List.of();
        private String total = "8050";
        private String medioPagoImpreso = "";
        private String medioPago = "Débito";
        private String cuenta = "";
        private List<String> categorias = List.of("Supermercado");
        private String confianza = "alta";

        Constructor problema(String v) { problema = v; return this; }
        Constructor comercio(String v) { comercio = v; return this; }
        Constructor fecha(String v) { fecha = v; return this; }
        Constructor hora(String v) { hora = v; return this; }
        Constructor items(List<LecturaTicketIa.Item> v) { items = v; return this; }
        Constructor sucursal(String v) { sucursal = v; return this; }
        Constructor subtotal(String v) { subtotal = v; return this; }
        Constructor descuentos(String v) { descuentos = v; return this; }
        Constructor lineasIlegibles(List<String> v) { lineasIlegibles = v; return this; }
        Constructor total(String v) { total = v; return this; }
        Constructor medioPagoImpreso(String v) { medioPagoImpreso = v; return this; }
        Constructor medioPago(String v) { medioPago = v; return this; }
        Constructor cuenta(String v) { cuenta = v; return this; }
        Constructor categorias(List<String> v) { categorias = v; return this; }
        Constructor confianza(String v) { confianza = v; return this; }

        LecturaTicketIa build() {
            return new LecturaTicketIa(problema, comercio, sucursal, fecha, hora, items, subtotal, descuentos, total,
                    medioPagoImpreso, medioPago, cuenta, categorias, confianza, lineasIlegibles);
        }
    }
}
