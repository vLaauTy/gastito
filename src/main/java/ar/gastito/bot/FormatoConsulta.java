package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ar.gastito.consulta.ConsultaService;
import ar.gastito.consulta.HerramientasConsulta;

/**
 * La respuesta a una pregunta: el texto de la IA (ya controlado) o, si no lo hay, los resultados de las
 * herramientas tal cual los calculó Java.
 */
@Component
public class FormatoConsulta {

    static final String NO_SE = """
            Todavía no sé responder eso 🤔
            Probá con algo como "¿cuánto gasté en kiosco este mes?" o "compará octubre con septiembre".""";

    /** Un rango que empieza antes del primer movimiento: lo que hay no es todo lo que se gastó. */
    static final String SIN_DATOS = "<i>(Todavía no registrabas gastos al principio de ese período: no hay datos "
            + "completos para comparar.)</i>";

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("d/M");

    private final FormatoMensajes formato;

    public FormatoConsulta(FormatoMensajes formato) {
        this.formato = formato;
    }

    public String respuesta(ConsultaService.Respuesta r) {
        if (r.texto() != null) {
            return "💬 " + html(r.texto());
        }
        if (r.resultados().isEmpty()) {
            return NO_SE;
        }
        List<String> partes = new ArrayList<>();
        partes.add("💬 Esto es lo que encontré:");
        r.resultados().forEach(resultado -> partes.add(numeros(resultado)));
        return String.join("\n\n", partes);
    }

    /** Un resultado de herramienta, en palabras y con los montos en formato argentino. */
    String numeros(Object resultado) {
        return switch (resultado) {
            case HerramientasConsulta.Total t -> "Del " + rango(t.desde(), t.hasta()) + filtros(t.filtros())
                    + ": <b>" + formato.pesos(t.total()) + "</b> en " + t.cantidad()
                    + (t.cantidad() == 1 ? " gasto." : " gastos.") + (t.conDatos() ? "" : " " + SIN_DATOS);
            case HerramientasConsulta.PorCategoria p -> {
                StringBuilder sb = new StringBuilder("Del " + rango(p.desde(), p.hasta()) + ": <b>"
                        + formato.pesos(p.total()) + "</b>");
                p.categorias().forEach(c -> sb.append("\n• ").append(html(c.categoria())).append(": ")
                        .append(formato.pesos(c.total())));
                yield sb.toString();
            }
            case HerramientasConsulta.Comparacion c -> "Del " + rango(c.a().desde(), c.a().hasta()) + ": <b>"
                    + formato.pesos(c.a().total()) + "</b>\nDel " + rango(c.b().desde(), c.b().hasta()) + ": <b>"
                    + formato.pesos(c.b().total()) + "</b>"
                    + (!c.a().conDatos() || !c.b().conDatos() ? "\n" + SIN_DATOS
                            : c.variacionPorcentaje() == null ? ""
                            : "\nDiferencia: " + signo(c.diferencia()) + formato.pesos(c.diferencia().abs()) + " ("
                                    + (c.variacionPorcentaje() > 0 ? "+" : "") + c.variacionPorcentaje() + "%)");
            case HerramientasConsulta.Listado l -> {
                StringBuilder sb = new StringBuilder("Del " + rango(l.desde(), l.hasta()) + ": " + l.encontrados()
                        + (l.encontrados() == 1 ? " gasto" : " gastos") + " por <b>" + formato.pesos(l.total())
                        + "</b>");
                l.movimientos().forEach(m -> sb.append("\n• ").append(DIA.format(m.fecha())).append(' ')
                        .append(html(m.descripcion())).append(" – ").append(formato.pesos(m.monto())));
                yield sb.toString();
            }
            case HerramientasConsulta.Producto p -> html(p.texto()) + ", del " + rango(p.desde(), p.hasta()) + ": "
                    + cantidad(p.unidades()) + (p.unidades().compareTo(BigDecimal.ONE) == 0 ? " unidad" : " unidades")
                    + " por <b>" + formato.pesos(p.total()) + "</b> en " + p.compras()
                    + (p.compras() == 1 ? " compra." : " compras.");
            case HerramientasConsulta.IngresosYDisponible i -> "Ingresos <b>" + formato.pesos(i.ingresos())
                    + "</b> · gastado <b>" + formato.pesos(i.gastado()) + "</b> · disponible <b>"
                    + formato.pesos(i.disponible()) + "</b>";
            default -> "";
        };
    }

    private static String filtros(HerramientasConsulta.Filtros f) {
        List<String> partes = new ArrayList<>();
        if (f.categoria() != null) {
            partes.add(f.categoria());
        }
        if (f.comercio() != null) {
            partes.add(f.comercio());
        }
        if (f.medioPago() != null) {
            partes.add(f.medioPago());
        }
        if (f.cuenta() != null) {
            partes.add(f.cuenta());
        }
        return partes.isEmpty() ? "" : " en " + html(String.join(", ", partes));
    }

    private static String rango(LocalDate desde, LocalDate hasta) {
        return DIA.format(desde) + " al " + DIA.format(hasta);
    }

    private static String signo(BigDecimal monto) {
        return monto.signum() < 0 ? "-" : "+";
    }

    private static String cantidad(BigDecimal unidades) {
        return unidades.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
