package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ar.gastito.recibo.ControlRecibo;
import ar.gastito.recibo.GrupoConcepto;
import ar.gastito.recibo.ReciboService;
import ar.gastito.recibo.TipoRecibo;
import ar.gastito.sueldo.SueldoService;

/**
 * La tarjeta de un recibo de sueldo que se acaba de leer: el detalle, lo calculado y lo cobrado, y el control con
 * lo que tiene de raro. El recibo ya está guardado: el único botón es para descartarlo.
 */
@Component
public class TarjetasRecibo {

    /** Descartar el recibo recién leído ("dr:<id del recibo>"). */
    static final String DESCARTAR = "dr";
    /** Descuentos que se detallan en la tarjeta; el resto queda en "…y N más". */
    static final int DESCUENTOS_VISIBLES = 8;
    /** Caracteres de un renglón del recibo. */
    static final int ANCHO = 32;

    private final FormatoMensajes formato;
    private final SueldoService sueldo;

    public TarjetasRecibo(FormatoMensajes formato, SueldoService sueldo) {
        this.formato = formato;
        this.sueldo = sueldo;
    }

    public Teclado teclado(long reciboId) {
        return Teclado.constructor()
                .fila(new Teclado.Boton("🗑 Descartar recibo", DESCARTAR + ":" + reciboId))
                .build();
    }

    public String descartado() {
        return "🗑 <b>Recibo descartado</b>\nNo guardé nada de ese recibo.";
    }

    /** @param reemplazo si ya había un recibo de ese mes y tipo, que esta lectura reemplazó */
    public String tarjeta(ReciboService.VistaRecibo r, boolean reemplazo) {
        List<String> lineas = new ArrayList<>();
        String mes = formato.mes(r.periodo());
        lineas.add("💼 <b>Recibo · " + html(Character.toUpperCase(mes.charAt(0)) + mes.substring(1)) + " · "
                + html(r.empleador()) + "</b>"
                + (r.tipo() == TipoRecibo.SUELDO_MENSUAL ? "" : " (" + r.tipo().getNombre() + ")"));
        lineas.add(detalle(r));
        String comparacion = comparacion(r);
        if (comparacion != null) {
            lineas.add(comparacion);
        }
        lineas.add(control(r));
        if (reemplazo) {
            lineas.add("🔄 Ya tenía el recibo de este mes: lo reemplacé.");
        }
        return String.join("\n", lineas);
    }

    /** El bruto, los descuentos con su detalle y el neto, con los importes contra el margen derecho. */
    private String detalle(ReciboService.VistaRecibo r) {
        StringBuilder bloque = new StringBuilder("<pre>");
        bloque.append(html(punteado("Bruto", formato.pesos(r.bruto())))).append('\n');
        bloque.append(html(punteado("Descuentos", "−" + formato.pesos(r.descuentos())))).append('\n');
        List<ReciboService.Renglon> descuentos = r.conceptos().stream()
                .filter(c -> c.grupo() == GrupoConcepto.DESCUENTO).toList();
        for (int i = 0; i < Math.min(DESCUENTOS_VISIBLES, descuentos.size()); i++) {
            ReciboService.Renglon d = descuentos.get(i);
            bloque.append(html(sangrado(d.descripcion(), "−" + formato.pesos(d.importe())))).append('\n');
        }
        if (descuentos.size() > DESCUENTOS_VISIBLES) {
            bloque.append("  …y ").append(descuentos.size() - DESCUENTOS_VISIBLES).append(" más\n");
        }
        bloque.append(html(punteado("💰 Neto", formato.pesos(r.neto())))).append("</pre>");
        return bloque.toString();
    }

    /** "Calculaste $1.114.400 · Cobraste $1.110.000 (−$4.400, −0,4%)"; null si no hay ni cálculo ni cobro. */
    private String comparacion(ReciboService.VistaRecibo r) {
        BigDecimal cobrado = r.cobro() == null ? null : r.cobro().monto();
        if (r.calculado() != null && cobrado != null) {
            return "🧮 " + comparar(r, cobrado);
        }
        if (cobrado == null) {
            return "⏳ Todavía no cargaste lo que cobraste de " + html(formato.mes(r.periodo()))
                    + ": cuando lo cargues, lo comparo con este recibo.";
        }
        return null;
    }

    private String comparar(ReciboService.VistaRecibo r, BigDecimal cobrado) {
        SueldoService.Comparacion c = sueldo.comparar(r.periodo(), r.calculado(), cobrado);
        return textoComparacion(c, formato, true);
    }

    /** "Calculaste $1.114.400 · Cobraste $1.110.000 (−$4.400, −0,4%)", con ⚠️ si pasa la tolerancia. */
    static String textoComparacion(SueldoService.Comparacion c, FormatoMensajes formato, boolean conAviso) {
        String signo = c.diferencia().signum() < 0 ? "−" : c.diferencia().signum() > 0 ? "+" : "";
        String porcentaje = c.porcentaje() == null ? ""
                : ", " + signo + c.porcentaje().abs().stripTrailingZeros().toPlainString().replace('.', ',') + "%";
        String texto = "Calculaste <b>" + formato.pesos(c.calculado()) + "</b> · Cobraste <b>"
                + formato.pesos(c.cobrado()) + "</b>";
        if (c.diferencia().signum() == 0) {
            return texto + " (igual)";
        }
        texto += " (" + signo + formato.pesos(c.diferencia().abs()) + porcentaje + ")";
        return conAviso && c.fuera() ? "⚠️ " + texto : texto;
    }

    /** Lo que tiene de raro, o que no encontré nada. */
    private String control(ReciboService.VistaRecibo r) {
        ControlRecibo.Resultado c = r.control();
        if (c.sinNadaRaro()) {
            return c.conCalculo() ? "✅ <b>El recibo coincide con tu cálculo</b>"
                    : "✅ No encontré nada raro" + (r.tipo() == TipoRecibo.SUELDO_MENSUAL
                            ? " (ese mes no tenés cálculo para comparar los conceptos)" : "");
        }
        StringBuilder texto = new StringBuilder("⚠️ <b>Para revisar:</b>");
        for (ControlRecibo.Hallazgo h : c.hallazgos()) {
            texto.append("\n• ").append(html(h.texto()));
        }
        return texto.toString();
    }

    /** "Bruto ........... $1.250.000": el importe llega al ancho de la tarjeta. */
    private static String punteado(String nombre, String importe) {
        int puntos = Math.max(2, ANCHO - nombre.length() - importe.length() - 2);
        return nombre + " " + ".".repeat(puntos) + " " + importe;
    }

    /** Los descuentos van sangrados y sin puntos: "  Jubilación 11%  −$137.500". */
    private static String sangrado(String nombre, String importe) {
        String completo = "  " + nombre;
        int lugar = ANCHO - importe.length() - 2;
        String corto = completo.length() > lugar ? completo.substring(0, Math.max(1, lugar - 1)).stripTrailing() + "…"
                : completo;
        return corto + " ".repeat(Math.max(2, ANCHO - corto.length() - importe.length())) + importe;
    }
}
