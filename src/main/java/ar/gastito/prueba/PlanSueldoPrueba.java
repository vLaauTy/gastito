package ar.gastito.prueba;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * La calculadora de sueldo de prueba: dos escalas inventadas (una paritaria en el medio de los seis meses) y las
 * horas de cada mes. Los montos no son los de ningún sueldo real.
 */
final class PlanSueldoPrueba {

    static final int MESES = 6;
    /** La paritaria rige desde hace 3 meses (el mes 4 de los 6). */
    static final int MESES_DE_LA_PARITARIA = 3;
    /** El mes (contando desde el más viejo) cuyo recibo trae un porcentaje de descuento distinto. */
    static final int MES_RARO = 1;
    /** Lo que se cobró de menos el último mes, que todavía no tiene recibo. */
    static final BigDecimal DIFERENCIA_COBRO = new BigDecimal("4400");

    /** @param montos el monto fijo (o el porcentaje, en los descuentos) por nombre de concepto */
    record Escala(YearMonth desde, BigDecimal valorHora, Map<String, BigDecimal> montos) {
    }

    private PlanSueldoPrueba() {
    }

    /** La escala de siempre y la de la paritaria (un 10% más, con los mismos descuentos). */
    static List<Escala> escalas(YearMonth hasta) {
        YearMonth inicio = hasta.minusMonths(MESES - 1L);
        return List.of(
                escala(inicio, "3000", "500000", "100000", "90000", "60000", "300000", "140000"),
                escala(hasta.minusMonths(MESES_DE_LA_PARITARIA - 1L), "3300", "550000", "110000", "99000",
                        "66000", "330000", "154000"));
    }

    /** Horas al 50%, al 100% y nocturnas del mes {@code indice} (0 es el más viejo). */
    static List<BigDecimal> horas(int indice) {
        int[][] mensuales = {{10, 6, 30}, {4, 0, 12}, {8, 2, 24}, {12, 4, 36}, {6, 8, 18}, {10, 6, 30}};
        int[] h = mensuales[indice % mensuales.length];
        return List.of(BigDecimal.valueOf(h[0]), BigDecimal.valueOf(h[1]), BigDecimal.valueOf(h[2]));
    }

    private static Escala escala(YearMonth desde, String hora, String basico, String presentismo, String adicional,
            String integracion, String viatico, String extraordinaria) {
        return new Escala(desde, new BigDecimal(hora), Map.ofEntries(
                Map.entry("Sueldo básico", new BigDecimal(basico)),
                Map.entry("Presentismo", new BigDecimal(presentismo)),
                Map.entry("Adicional CCT", new BigDecimal(adicional)),
                Map.entry("Asignación integración social", new BigDecimal(integracion)),
                Map.entry("Viático", new BigDecimal(viatico)),
                Map.entry("Asignación extraordinaria", new BigDecimal(extraordinaria)),
                Map.entry("Jubilación", new BigDecimal("11")),
                Map.entry("Obra social", new BigDecimal("3")),
                Map.entry("Ley 19.032", new BigDecimal("3")),
                Map.entry("Sindicato", new BigDecimal("3"))));
    }
}
