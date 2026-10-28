package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import ar.gastito.ia.CostosIaService;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.TopeIaService;

/** Los mensajes de costos de la IA: /costos y los avisos del tope mensual. Los montos van en dólares ("USD 1,62"). */
@Component
public class FormatoCostos {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final BigDecimal UN_CENTAVO = new BigDecimal("0.01");

    private final CostosIaService costos;
    private final FormatoMensajes formato;
    private final Clock clock;

    public FormatoCostos(CostosIaService costos, FormatoMensajes formato, Clock clock) {
        this.costos = costos;
        this.formato = formato;
        this.clock = clock;
    }

    /** "USD 1,62"; los montos de menos de un centavo llevan hasta 6 decimales ("USD 0,000242"). */
    public static String usd(BigDecimal monto) {
        boolean chico = monto.signum() != 0 && monto.abs().compareTo(UN_CENTAVO) < 0;
        return "USD " + formato(chico ? "#,##0.0000##" : "#,##0.00").format(monto);
    }

    /** "USD 2" para un tope redondo, "USD 2,50" si no. */
    static String usdTope(BigDecimal tope) {
        return "USD " + formato(tope.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00").format(tope);
    }

    /** /costos: el total del mes, la proyección y el desglose por propósito. */
    public String comando() {
        YearMonth mes = YearMonth.now(clock);
        CostosIaService.Panel p = costos.panel(mes);
        String nombre = formato.mes(mes);
        if (p.actual().llamadas() == 0) {
            return "En " + html(nombre) + " todavía no se usó la IA.";
        }
        StringBuilder sb = new StringBuilder("💸 <b>IA en ").append(html(nombre)).append("</b>\n");
        sb.append("Llevás <b>").append(usd(p.actual().costoUsd())).append("</b> en ")
                .append(p.actual().llamadas()).append(p.actual().llamadas() == 1 ? " llamada" : " llamadas");
        if (p.tope().porcentaje() != null) {
            sb.append(" (").append(p.tope().porcentaje()).append("% del tope de ").append(usdTope(p.tope().topeUsd()))
                    .append(")");
        }
        sb.append(".\nProyección a fin de mes: <b>").append(usd(p.proyeccion().costoUsd())).append("</b>");
        if (p.anterior().llamadas() > 0) {
            sb.append("\nEl mes pasado: ").append(usd(p.anterior().costoUsd()));
        }
        List<CostosIaService.PorProposito> usados = p.porProposito().stream().filter(x -> x.llamadas() > 0).toList();
        sb.append("\n");
        for (CostosIaService.PorProposito x : usados) {
            sb.append("\n• ").append(nombre(x.proposito())).append(": ").append(x.llamadas())
                    .append(x.llamadas() == 1 ? " llamada" : " llamadas").append(" · ").append(usd(x.costoUsd()));
        }
        return sb.toString();
    }

    /** "La IA lleva USD 1,62 este mes (81% del tope de USD 2)", o el aviso del 100 %. */
    public Accion.Enviar aviso(TopeIaService.Aviso a) {
        String texto = a.umbral() >= 100
                ? "⚠️ <b>La IA llegó al tope del mes.</b>\nLleva " + usd(a.gastadoUsd()) + " este mes (" + a.porcentaje()
                        + "% del tope de " + usdTope(a.topeUsd()) + "). No bloqueo nada, solo te aviso."
                : "⚠️ <b>La IA lleva " + usd(a.gastadoUsd()) + " este mes</b> (" + a.porcentaje()
                        + "% del tope de " + usdTope(a.topeUsd()) + ").";
        return Accion.Enviar.texto(texto);
    }

    static String nombre(PropositoIa proposito) {
        return switch (proposito) {
            case TEXTO -> "Texto";
            case AUDIO -> "Audio";
            case TICKET -> "Ticket";
            case RECIBO -> "Recibo";
            case RESUMEN -> "Resumen";
            case CONSULTA -> "Consulta";
        };
    }

    private static DecimalFormat formato(String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(ES_AR);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat(patron, simbolos);
    }
}
