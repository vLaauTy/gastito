package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.time.Clock;
import java.time.YearMonth;

import org.springframework.stereotype.Component;

import ar.gastito.consulta.HerramientasConsulta;

/**
 * "¿Cuánto me queda?" (el botón del menú o /disponible): los ingresos del mes menos lo gastado. Lo calcula Java con
 * la misma herramienta que usan las preguntas, sin llamar a la IA.
 */
@Component
public class ComandoDisponible {

    private final HerramientasConsulta herramientas;
    private final FormatoMensajes formato;
    private final Clock clock;

    public ComandoDisponible(HerramientasConsulta herramientas, FormatoMensajes formato, Clock clock) {
        this.herramientas = herramientas;
        this.formato = formato;
        this.clock = clock;
    }

    public String responder() {
        YearMonth mes = YearMonth.now(clock);
        HerramientasConsulta.IngresosYDisponible d = herramientas.ingresosYDisponible(mes);
        String titulo = "💰 <b>" + html(capitalizar(formato.mes(mes))) + "</b>\n";
        if (d.ingresos().signum() == 0) {
            return titulo + "Todavía no cargaste ingresos este mes, así que no sé cuánto te queda.\nGastado: <b>"
                    + formato.pesos(d.gastado()) + "</b>";
        }
        return titulo + "Te queda <b>" + formato.pesos(d.disponible()) + "</b>\nIngresos: " + formato.pesos(d.ingresos())
                + " · Gastado: " + formato.pesos(d.gastado())
                + (d.porcentajeGastado() == null ? "" : " (" + d.porcentajeGastado() + "%)");
    }

    private static String capitalizar(String texto) {
        return texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }
}
