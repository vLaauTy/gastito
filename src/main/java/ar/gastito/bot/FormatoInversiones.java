package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.stereotype.Component;

import ar.gastito.inversion.PanelInversiones;

/** Los textos de las inversiones en el bot: lo que vale, lo puesto y el rendimiento (ganancia o pérdida). */
@Component
public class FormatoInversiones {

    private static final DecimalFormatSymbols ES_AR = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR"));

    private final FormatoMensajes formato;

    public FormatoInversiones(FormatoMensajes formato) {
        this.formato = formato;
    }

    public String pesos(BigDecimal monto) {
        return formato.pesos(monto);
    }

    /** "+$20.000 (+2,35 %)", "−$30.000 (−3,33 %)" o "$0": una ganancia o una pérdida, nunca un ingreso. */
    public String rendimiento(BigDecimal pesos, BigDecimal porcentaje) {
        if (pesos.signum() == 0) {
            return "$0";
        }
        String signo = pesos.signum() > 0 ? "+" : "−";
        String texto = signo + formato.pesos(pesos.abs());
        return porcentaje == null ? texto : texto + " (" + signo + porcentaje(porcentaje.abs()) + ")";
    }

    /** "2,35 %". */
    static String porcentaje(BigDecimal valor) {
        return new DecimalFormat("#,##0.##", ES_AR).format(valor) + " %";
    }

    /** "hoy", "ayer", "hace 7 días". */
    public static String hace(Long dias) {
        if (dias == null) {
            return "nunca";
        }
        return dias <= 0 ? "hoy" : dias == 1 ? "ayer" : "hace " + dias + " días";
    }

    /** "ganancia de" o "pérdida de", para decirlo con palabras. */
    public static String gananciaOPerdida(BigDecimal pesos) {
        return pesos.signum() < 0 ? "📉 pérdida" : "📈 ganancia";
    }

    /** El panel completo: el total arriba y una línea por cuenta y por posición, con lo reservado. */
    public String panel(PanelInversiones.Panel p) {
        if (p.cuentas().isEmpty()) {
            return "📈 Todavía no tenés inversiones cargadas.\nContame cuánto tenés y dónde, por ejemplo "
                    + "<i>tengo 850.000 invertidos en Cocos</i>: es ahorro, no cuenta como ingreso.";
        }
        StringBuilder sb = new StringBuilder("📈 <b>Tenés invertido ").append(pesos(p.valor())).append("</b>\n")
                .append("Pusiste ").append(pesos(p.aportado())).append(" · rendimiento ")
                .append(rendimiento(p.rendimiento(), p.porcentaje()));
        if (p.actualizado() != null) {
            sb.append(" · actualizado ").append(hace(p.diasSinActualizar()));
        }
        for (PanelInversiones.EstadoCuenta c : p.cuentas()) {
            sb.append("\n\n").append(cuenta(c));
        }
        return sb.toString();
    }

    /** Una cuenta: lo que vale, lo puesto, el rendimiento, sus posiciones y lo reservado. */
    public String cuenta(PanelInversiones.EstadoCuenta c) {
        StringBuilder sb = new StringBuilder("🏦 <b>").append(html(c.nombre())).append("</b>: ").append(pesos(c.valor()))
                .append(" · puesto ").append(pesos(c.aportado())).append(" · ")
                .append(rendimiento(c.rendimiento(), c.porcentaje()));
        if (c.actualizado() != null) {
            sb.append("\n   Actualizado ").append(hace(c.diasSinActualizar()));
        }
        if (!c.posiciones().isEmpty()) {
            for (PanelInversiones.Tenencia t : c.tenencias()) {
                sb.append("\n   • ").append(html(t.nombre())).append(": ").append(pesos(t.valor()));
                if (t.sinValuar()) {
                    sb.append(" (sin valuar)");
                } else if (t.rendimiento().signum() != 0) {
                    sb.append(" (").append(rendimiento(t.rendimiento(), t.porcentaje())).append(")");
                }
            }
        }
        for (PanelInversiones.Reserva r : c.reservas()) {
            sb.append("\n   🎯 Reservado para ").append(html(r.meta())).append(": ").append(pesos(r.monto()));
        }
        if (!c.reservas().isEmpty()) {
            sb.append("\n   Libre: ").append(pesos(c.libre()));
        }
        return sb.toString();
    }
}
