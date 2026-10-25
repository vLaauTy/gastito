package ar.gastito.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ControlNumerosTest {

    /** Los datos de un resumen semanal, como los arma Java. */
    static final String DATOS = """
            {
              "periodo": {"tipo": "SEMANAL", "desde": "2026-10-13", "hasta": "2026-10-19", "parcial": false, "dias": 7},
              "totalGastado": 52300.00,
              "cantidadGastos": 23,
              "comparacion": {"total": 46700.00, "variacionPorcentaje": 12},
              "porCategoria": [
                {"nombre": "Supermercado", "total": 21400.00, "cantidad": 3, "variacionPorcentaje": -33},
                {"nombre": "Kiosco", "total": 14800.00, "cantidad": 11, "variacionPorcentaje": 40}
              ],
              "hormiga": {"cantidad": 11, "total": 14800.00, "promedio": 1345.45, "porSemana": 4.7},
              "escenariosAhorro": [
                {"tipo": "HORMIGA", "descripcion": "Bajar los gastos hormiga a 3 por semana",
                 "valorActual": 4.7, "valorObjetivo": 3, "ahorroMensual": 9900.00},
                {"tipo": "CATEGORIA_MAS_GRANDE", "descripcion": "Gastar un 10% menos en Supermercado",
                 "ahorroMensual": 9300.00}
              ],
              "aclaraciones": ["1 gasto sin medio de pago (Sin especificar), por 3000.00"]
            }""";

    private final Set<BigDecimal> permitidos = permitidos();

    private static Set<BigDecimal> permitidos() {
        try {
            JsonNode json = new ObjectMapper().readTree(DATOS);
            return ControlNumeros.numerosDe(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ControlNumeros.Resultado revisar(String texto) {
        return ControlNumeros.revisar(texto, permitidos);
    }

    @Test
    void unTextoConLosNumerosDeLosDatosEsValido() {
        assertThat(revisar("""
                Gastaste $52.300, un 12% más que la semana pasada. En kiosco fueron 11 compras chicas por $14.800 \
                (4,7 por semana). Si bajás a 3 por semana, ahorrás unos $9.900 por mes.""").valido()).isTrue();
    }

    @Test
    void unNumeroQueNoEstaEnLosDatosDescartaElTexto() {
        ControlNumeros.Resultado r = revisar("Gastaste $52.300 y si dejás el kiosco ahorrás $25.000 por mes.");

        assertThat(r.valido()).isFalse();
        assertThat(r.inventados()).containsExactly("25.000");
    }

    @Test
    void losRedondeosChicosSeAceptan() {
        assertThat(revisar("Gastaste unos $52.000.").valido()).isTrue(); // 0,6% de diferencia
        assertThat(revisar("En kiosco, casi $15.000.").valido()).isTrue(); // 1,4%
        assertThat(revisar("En kiosco, unos 15 mil.").valido()).isTrue();
        assertThat(revisar("Un 13% más que la semana pasada.").valido()).isTrue(); // 1 punto
        assertThat(revisar("En kiosco, $16.000.").valido()).isFalse(); // 8%
        assertThat(revisar("Un 15% más.").valido()).isFalse(); // 3 puntos
    }

    @Test
    void lasCantidadesTienenQueSerExactas() {
        assertThat(revisar("Fueron 11 compras chicas.").valido()).isTrue();
        assertThat(revisar("Fueron 15 compras chicas.").valido()).isFalse();
        assertThat(revisar("4,7 por semana.").valido()).isTrue();
        assertThat(revisar("5 por semana.").valido()).isFalse();
    }

    @Test
    void elSignoNoCuentaYLosNumerosDentroDeLosTextosValen() {
        assertThat(revisar("El súper bajó un 33%.").valido()).isTrue(); // variación -33
        assertThat(revisar("Gastando un 10% menos en súper ahorrás $9.300 por mes.").valido()).isTrue();
        assertThat(revisar("Hay 1 gasto sin medio de pago, de $3.000.").valido()).isTrue();
    }

    @Test
    void lasFechasDelPeriodoValenYLasHorasNoSeCuentan() {
        assertThat(revisar("Tu semana del 13 al 19 de octubre de 2026.").valido()).isTrue();
        assertThat(revisar("Te lo mando el domingo a las 21:00.").valido()).isTrue();
        assertThat(revisar("Del 13 al 20 de octubre.").valido()).isFalse();
    }

    @Test
    void sinNumerosEsValido() {
        assertThat(revisar("Vas bien, seguí así.").valido()).isTrue();
        assertThat(ControlNumeros.revisar(List.of("Gastaste $52.300.", "Consejo: bajá el kiosco."), permitidos)
                .valido()).isTrue();
    }

    @Test
    void leeLosNumerosComoEstanEscritos() {
        assertThat(ControlNumeros.numeros("$ 1.234,50, 12 %, 1,5 millones y 25k"))
                .extracting(ControlNumeros.Numero::valor)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1234.50"), new BigDecimal("12"), new BigDecimal("1500000"),
                        new BigDecimal("25000"));
    }
}
