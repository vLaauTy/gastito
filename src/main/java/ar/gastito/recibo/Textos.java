package ar.gastito.recibo;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.Locale;

/** Textos de los controles del recibo: pesos, porcentajes y la clave con la que se reconoce un renglón. */
final class Textos {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private Textos() {
    }

    /** "$3.700", "$1.500,50". */
    static String pesos(BigDecimal monto) {
        BigDecimal m = monto.stripTrailingZeros();
        return "$" + formato(m.scale() > 0 ? "#,##0.00" : "#,##0").format(monto);
    }

    /** "3%", "2,5%". */
    static String porcentaje(BigDecimal valor) {
        BigDecimal m = valor.stripTrailingZeros();
        return formato(m.scale() > 0 ? "#,##0.0#" : "#,##0").format(valor) + "%";
    }

    /**
     * Con qué se reconoce un renglón del recibo para reusar el mapeo: sin tildes, en minúsculas y sin signos. Los
     * números se conservan: "horas extra 50%" y "horas extra 100%" son conceptos distintos.
     */
    static String claveDeMapeo(String descripcion) {
        String sinTildes = Normalizer.normalize(descripcion == null ? "" : descripcion, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String clave = sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ")
                .strip();
        return clave.length() > 150 ? clave.substring(0, 150).strip() : clave;
    }

    private static DecimalFormat formato(String patron) {
        return new DecimalFormat(patron, DecimalFormatSymbols.getInstance(ES_AR));
    }
}
