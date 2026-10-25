package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.consulta.ConsultaService;
import ar.gastito.consulta.HerramientasConsulta;

class FormatoConsultaTest {

    private final FormatoConsulta formato = new FormatoConsulta(new FormatoMensajes(RELOJ));

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_25 = LocalDate.of(2026, 10, 25);

    private static HerramientasConsulta.Total total(LocalDate desde, LocalDate hasta, String monto, int cantidad) {
        return new HerramientasConsulta.Total(desde, hasta, HerramientasConsulta.Filtros.NINGUNO,
                new BigDecimal(monto), cantidad);
    }

    @Test
    void elTextoDeLaIaSeEscapa() {
        assertThat(formato.respuesta(new ConsultaService.Respuesta("Llevás <$14.800>", List.of(), false, null)))
                .isEqualTo("💬 Llevás &lt;$14.800&gt;");
    }

    @Test
    void sinTextoNiResultadosNoSabe() {
        assertThat(formato.respuesta(new ConsultaService.Respuesta(null, List.of(), false, null)))
                .isEqualTo(FormatoConsulta.NO_SE);
    }

    @Test
    void unaComparacionMuestraLaDiferenciaQueCalculoJava() {
        var c = new HerramientasConsulta.Comparacion(total(OCT_1, OCT_25, "120000.00", 30),
                total(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 25), "100000.00", 28),
                new BigDecimal("20000.00"), 20, List.of());

        assertThat(formato.numeros(c)).isEqualTo("""
                Del 1/10 al 25/10: <b>$120.000</b>
                Del 1/9 al 25/9: <b>$100.000</b>
                Diferencia: +$20.000 (+20%)""");
    }

    @Test
    void productoListadoEIngresos() {
        assertThat(formato.numeros(new HerramientasConsulta.Producto("Seven Up", OCT_1, OCT_25,
                new BigDecimal("3.000"), new BigDecimal("5400.00"), 2, List.of())))
                .isEqualTo("Seven Up, del 1/10 al 25/10: 3 unidades por <b>$5.400</b> en 2 compras.");
        assertThat(formato.numeros(new HerramientasConsulta.Listado(OCT_1, OCT_25, 1, new BigDecimal("1500.00"),
                List.of(new HerramientasConsulta.MovimientoListado(LocalDate.of(2026, 10, 3), "Alfajor", "Kiosco",
                        "Efectivo", new BigDecimal("1500.00"))))))
                .isEqualTo("Del 1/10 al 25/10: 1 gasto por <b>$1.500</b>\n• 3/10 Alfajor – $1.500");
        assertThat(formato.numeros(new HerramientasConsulta.IngresosYDisponible("2026-10",
                new BigDecimal("380000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("380000.00"),
                new BigDecimal("24650.00"), new BigDecimal("355350.00"), 6)))
                .isEqualTo("Ingresos <b>$380.000</b> · gastado <b>$24.650</b> · disponible <b>$355.350</b>");
    }
}
