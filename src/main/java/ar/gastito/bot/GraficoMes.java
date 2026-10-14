package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Component;

import ar.gastito.movimiento.TotalPorCategoria;

/**
 * El resumen del mes como gráfico de barras de texto, dentro de un bloque monoespaciado para que las columnas
 * queden alineadas:
 *
 * <pre>
 * 🛒 Supermercado    $30.000 ▇▇▇▇▇▇▇▇▇▇▇▇
 * 🍬 Kiosco           $5.200 ▇▇
 * </pre>
 */
@Component
public class GraficoMes {

    static final int ANCHO_NOMBRE = 14;
    static final int BARRA_MAXIMA = 12;
    static final String BLOQUE = "▇";

    private final FormatoMensajes formato;

    public GraficoMes(FormatoMensajes formato) {
        this.formato = formato;
    }

    public String barras(List<TotalPorCategoria> totales) {
        if (totales.isEmpty()) {
            return "";
        }
        BigDecimal maximo = totales.stream().map(TotalPorCategoria::total).max(BigDecimal::compareTo).orElseThrow();
        List<String> montos = totales.stream().map(t -> formato.pesos(t.total())).toList();
        int anchoMonto = montos.stream().mapToInt(String::length).max().orElse(0);

        StringBuilder sb = new StringBuilder("<pre>");
        for (int i = 0; i < totales.size(); i++) {
            TotalPorCategoria t = totales.get(i);
            String emoji = t.emoji() == null || t.emoji().isBlank() ? "•" : t.emoji();
            sb.append(emoji).append(' ')
                    .append(html(rellenar(nombreCorto(t.categoria()), ANCHO_NOMBRE))).append(' ')
                    .append(" ".repeat(anchoMonto - montos.get(i).length())).append(montos.get(i)).append(' ')
                    .append(BLOQUE.repeat(largoBarra(t.total(), maximo)));
            if (i < totales.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.append("</pre>").toString();
    }

    /** Proporcional al máximo, de 1 a {@value #BARRA_MAXIMA} bloques. */
    static int largoBarra(BigDecimal total, BigDecimal maximo) {
        if (maximo.signum() <= 0 || total.signum() <= 0) {
            return 0;
        }
        int largo = total.multiply(BigDecimal.valueOf(BARRA_MAXIMA))
                .divide(maximo, 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.max(1, Math.min(BARRA_MAXIMA, largo));
    }

    /** Sin lo que va entre paréntesis y recortado: "Servicios (luz, gas…)" → "Servicios". */
    static String nombreCorto(String nombre) {
        String sinParentesis = nombre.replaceAll("\\s*\\(.*\\)", "").trim();
        return sinParentesis.length() <= ANCHO_NOMBRE
                ? sinParentesis
                : sinParentesis.substring(0, ANCHO_NOMBRE - 1).trim() + "…";
    }

    private static String rellenar(String texto, int ancho) {
        return texto.length() >= ancho ? texto : texto + " ".repeat(ancho - texto.length());
    }
}
