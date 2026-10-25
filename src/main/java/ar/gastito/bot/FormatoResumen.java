package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.movimiento.TotalPorCategoria;
import ar.gastito.resumen.DatosResumen;
import ar.gastito.resumen.PeriodoResumen;
import ar.gastito.resumen.Resumen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;

/**
 * Un resumen en Telegram: los números que calculó Java y, si pasó el control, lo que notó la IA.
 *
 * <pre>
 * 📊 Tu semana · 13 al 19 de octubre
 * Gastaste $52.300 en 23 gastos (+12% que la semana pasada)
 *
 * 🛒 Supermercado    $21.400 ▇▇▇▇▇▇
 * 🍬 Kiosco          $14.800 ▇▇▇▇
 *
 * 🐜 Gastos hormiga: 11 compras chicas, $14.800
 * 🏆 Lo que más compraste: Seven Up lata (4)
 *
 * 💡 Lo que noté
 * • …
 * 👉 Consejo: …
 * </pre>
 */
@Component
public class FormatoResumen {

    /** Datos del botón: "va:S:20261019:20261026" (tipo, desde y hasta del primer y último período ofrecido). */
    static final String VER_ANTERIORES = "va";
    static final int CATEGORIAS = 5;

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DIA_Y_MES = DateTimeFormatter.ofPattern("d 'de' MMMM", ES_AR);
    private static final DateTimeFormatter MES_Y_ANIO = DateTimeFormatter.ofPattern("MMMM yyyy", ES_AR);
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM", ES_AR);
    private static final DateTimeFormatter DIA_SEMANA = DateTimeFormatter.ofPattern("EEEE d", ES_AR);
    private static final DateTimeFormatter CLAVE = DateTimeFormatter.BASIC_ISO_DATE;

    private final FormatoMensajes formato;
    private final GraficoMes grafico;
    private final String urlDashboard;

    public FormatoResumen(FormatoMensajes formato, GraficoMes grafico, DashboardProperties dashboard) {
        this.formato = formato;
        this.grafico = grafico;
        this.urlDashboard = dashboard.url();
    }

    /** @param anterioresSinVer cuántos resúmenes anteriores quedaron en "Ver anteriores" (0 si ninguno) */
    public String mensaje(ResumenService.Armado armado, int anterioresSinVer) {
        Resumen r = armado.resumen();
        DatosResumen d = armado.datos();
        boolean semanal = r.getTipo() == TipoResumen.SEMANAL;
        List<String> partes = new ArrayList<>();

        StringBuilder encabezado = new StringBuilder("📊 <b>").append(semanal ? "Tu semana" : "Tu mes")
                .append("</b> · ").append(semanal ? rango(r.getDesde(), d.periodo().fin())
                        : mayuscula(MES_Y_ANIO.format(r.getDesde())));
        if (d.periodo().parcial()) {
            encabezado.append("\n<i>").append(semanal ? "Semana" : "Mes").append(" parcial, hasta el ")
                    .append(DIA_SEMANA.format(d.periodo().hasta())).append("</i>");
        }
        if (d.cantidadGastos() == 0) {
            encabezado.append("\nNo registraste gastos ").append(semanal ? "esta semana." : "este mes.");
        } else {
            encabezado.append("\nGastaste <b>").append(formato.pesos(d.totalGastado())).append("</b> en ")
                    .append(d.cantidadGastos()).append(d.cantidadGastos() == 1 ? " gasto" : " gastos")
                    .append(comparacion(d, semanal, r));
        }
        partes.add(encabezado.toString());

        if (!d.porCategoria().isEmpty()) {
            partes.add(grafico.barras(d.porCategoria().stream().limit(CATEGORIAS)
                    .map(c -> new TotalPorCategoria(c.nombre(), c.emoji(), c.total())).toList()));
        }

        List<String> datos = new ArrayList<>();
        if (d.hormiga().cantidad() > 0) {
            datos.add("🐜 Gastos hormiga: " + d.hormiga().cantidad()
                    + (d.hormiga().cantidad() == 1 ? " compra chica, " : " compras chicas, ")
                    + formato.pesos(d.hormiga().total()));
        }
        if (!d.productos().porUnidades().isEmpty()) {
            DatosResumen.Producto p = d.productos().porUnidades().get(0);
            datos.add("🏆 Lo que más compraste: " + html(p.nombre()) + " (" + unidades(p.unidades()) + ")");
        }
        if (d.diasSinGastos() > 0) {
            datos.add("🌱 Días sin gastos: " + d.diasSinGastos());
        }
        if (d.sinEspecificar() != null && d.sinEspecificar().cantidad() > 0) {
            datos.add("❔ " + d.sinEspecificar().cantidad() + (d.sinEspecificar().cantidad() == 1 ? " gasto" : " gastos")
                    + " sin medio de pago (" + formato.pesos(d.sinEspecificar().total()) + ")");
        }
        if (d.ingresos() != null) {
            datos.add("💵 Ingresos <b>" + formato.pesos(d.ingresos().total()) + "</b> · Disponible <b>"
                    + formato.pesos(d.ingresos().disponible()) + "</b>"
                    + (d.ingresos().porcentajeGastado() == null ? ""
                            : "\nGastaste el " + d.ingresos().porcentajeGastado() + "% de lo que entró."));
        }
        if (d.costoIaUsd() != null) {
            datos.add("🤖 La IA costó " + formato.dolares(d.costoIaUsd()) + " en el mes.");
        }
        if (d.notas() != null) {
            d.notas().forEach(n -> datos.add("<i>ℹ️ " + html(n) + "</i>"));
        }
        if (!datos.isEmpty()) {
            partes.add(String.join("\n", datos));
        }

        if (!r.getObservaciones().isEmpty() || r.getConsejo() != null) {
            StringBuilder ia = new StringBuilder("💡 <b>Lo que noté</b>");
            r.getObservaciones().forEach(o -> ia.append("\n• ").append(html(o)));
            if (r.getConsejo() != null) {
                ia.append("\n👉 <b>Consejo:</b> ").append(html(r.getConsejo()));
            }
            partes.add(ia.toString());
        }
        if (d.pocosDatos()) {
            partes.add("👉 " + DatosResumen.POCOS_DATOS);
        }

        String pie = "🖥 Más detalle en el dashboard: <a href=\"" + html(urlDashboard) + "\">" + html(urlDashboard)
                + "</a>";
        if (anterioresSinVer > 0) {
            pie += "\n📬 Tenés " + anterioresSinVer + (anterioresSinVer == 1 ? " resumen anterior" : " resúmenes anteriores")
                    + " sin ver.";
        }
        partes.add(pie);
        return String.join("\n\n", partes);
    }

    /** El botón "Ver anteriores" de un envío automático, o ninguno. */
    public Teclado teclado(ResumenService.Envio envio) {
        int ofrecidos = ResumenService.ofrecidos(envio);
        if (ofrecidos == 0) {
            return Teclado.NINGUNO;
        }
        List<PeriodoResumen> anteriores = envio.anteriores();
        LocalDate desde = anteriores.get(anteriores.size() - ofrecidos).desde();
        LocalDate hasta = anteriores.get(anteriores.size() - 1).desde();
        String datos = VER_ANTERIORES + ":" + (envio.periodo().tipo() == TipoResumen.SEMANAL ? "S" : "M") + ":"
                + CLAVE.format(desde) + ":" + CLAVE.format(hasta);
        return Teclado.constructor().fila(new Teclado.Boton("📬 Ver anteriores", datos)).build();
    }

    /** Lee los datos del botón: el tipo y el rango, o null si no son de "Ver anteriores". */
    record Anteriores(TipoResumen tipo, LocalDate desde, LocalDate hasta) {
    }

    static Anteriores leerAnteriores(String datos) {
        String[] partes = datos == null ? new String[0] : datos.split(":");
        if (partes.length != 4 || !VER_ANTERIORES.equals(partes[0])) {
            return null;
        }
        try {
            TipoResumen tipo = switch (partes[1]) {
                case "S" -> TipoResumen.SEMANAL;
                case "M" -> TipoResumen.MENSUAL;
                default -> throw new IllegalArgumentException(partes[1]);
            };
            return new Anteriores(tipo, LocalDate.parse(partes[2], CLAVE), LocalDate.parse(partes[3], CLAVE));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "(+12% que la semana pasada)", "(-5% que septiembre)", "(la semana pasada no hubo gastos)". */
    private String comparacion(DatosResumen d, boolean semanal, Resumen r) {
        String contra = semanal ? "la semana pasada" : MES.format(r.getDesde().minusMonths(1));
        String altura = d.periodo().parcial() ? " a esta altura" : "";
        Integer variacion = d.comparacion().variacionPorcentaje();
        if (variacion == null) {
            return " (" + contra + altura + " no hubo gastos)";
        }
        if (variacion == 0) {
            return " (igual que " + contra + altura + ")";
        }
        return " (" + (variacion > 0 ? "+" : "") + variacion + "% que " + contra + altura + ")";
    }

    /** "13 al 19 de octubre", "27 de octubre al 2 de noviembre". */
    static String rango(LocalDate desde, LocalDate hasta) {
        if (desde.getMonth() == hasta.getMonth()) {
            return desde.getDayOfMonth() + " al " + DIA_Y_MES.format(hasta);
        }
        return DIA_Y_MES.format(desde) + " al " + DIA_Y_MES.format(hasta);
    }

    private static String unidades(BigDecimal unidades) {
        return unidades.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String mayuscula(String texto) {
        return texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }
}
