package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import ar.gastito.interpretacion.ParserHorasSueldo;
import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;

/**
 * La calculadora de sueldo en el bot: "este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas" o
 * {@code /calcular 10 6 30} arman la tarjeta con el resultado (Guardar / Editar / Cancelar), y {@code /calculo}
 * muestra el del mes. Las horas viajan en el botón ("cg:202610:10_6_30"), así no hace falta guardar nada hasta que
 * el usuario toca Guardar.
 */
@Component
public class ComandosSueldo {

    static final String GUARDAR = "cg";
    static final String EDITAR = "ce";
    static final String CANCELAR = "cc";
    static final int ANCHO = 32;

    private final SueldoService sueldo;
    private final FormatoMensajes formato;
    private final Clock clock;

    public ComandosSueldo(SueldoService sueldo, FormatoMensajes formato, Clock clock) {
        this.sueldo = sueldo;
        this.formato = formato;
        this.clock = clock;
    }

    // --- Entradas ---

    /** Si el mensaje cuenta las horas del mes ("este mes hice 10 horas al 50…"). */
    public boolean pareceCalculo(String texto) {
        return ParserHorasSueldo.parsear(texto, sueldo.conceptosDeHoras()).isPresent();
    }

    public Accion calcularDeTexto(String texto) {
        Optional<Map<Long, BigDecimal>> horas = ParserHorasSueldo.parsear(texto, sueldo.conceptosDeHoras());
        if (horas.isEmpty()) {
            return Accion.Enviar.texto(ayuda());
        }
        return calcular(horas.get());
    }

    /**
     * Las horas que reconoció la IA, ya normalizadas ("10 al 50, 6 al 100, 30 nocturnas"). Si no se entendió
     * ninguna, se preguntan.
     */
    public Accion calcularDeHoras(String horas, String prefijo) {
        Optional<Map<Long, BigDecimal>> leidas = ParserHorasSueldo.cantidades(horas, sueldo.conceptosDeHoras());
        if (leidas.isEmpty()) {
            return Accion.Enviar.texto(prefijo + ayuda());
        }
        return calcular(leidas.get()).conPrefijo(prefijo);
    }

    /** @param texto el mensaje entero, con el comando ("/calcular 10 6 30") */
    public Accion calcularDeComando(String texto) {
        String[] partes = texto.strip().split("\\s+", 2);
        String argumentos = partes.length > 1 ? partes[1] : "";
        Optional<List<BigDecimal>> cantidades = ParserHorasSueldo.posicionales(argumentos);
        if (cantidades.isEmpty()) {
            return Accion.Enviar.texto(argumentos.isBlank() ? ayuda() : "No entendí las horas 🤔\n" + ayuda());
        }
        try {
            return calcular(sueldo.horasPorOrden(cantidades.get()));
        } catch (IllegalArgumentException e) {
            return Accion.Enviar.texto(html(e.getMessage()));
        }
    }

    private Accion.Enviar calcular(Map<Long, BigDecimal> horas) {
        YearMonth mes = YearMonth.now(clock);
        SueldoService.VistaCalculo v;
        try {
            v = sueldo.calcular(mes, horas, Map.of());
        } catch (IllegalArgumentException e) {
            return Accion.Enviar.texto(html(e.getMessage()));
        }
        return Accion.Enviar.conBotones(tarjeta(v), Teclado.constructor()
                .fila(boton("✅ Guardar", GUARDAR, mes, horas), boton("✏️ Editar", EDITAR, mes, horas),
                        boton("❌ Cancelar", CANCELAR, mes, horas))
                .build());
    }

    /** {@code /calculo}: el cálculo guardado del mes, concepto por concepto. */
    public Accion verCalculo() {
        YearMonth mes = YearMonth.now(clock);
        Optional<CalculoSueldo> calculo = sueldo.calculoDelMes(mes);
        if (calculo.isEmpty()) {
            return Accion.Enviar.conMenu("Todavía no calculaste el sueldo de " + html(formato.mes(mes)) + ".\n"
                    + ayuda());
        }
        CalculoSueldo c = calculo.get();
        List<String> lineas = new ArrayList<>();
        for (CalculoSueldo.Linea l : c.getLineas()) {
            if (l.getImporte().signum() == 0) {
                continue;
            }
            String nombre = l.getCantidad() != null ? l.getNombre() + " (" + horas(l.getCantidad()) + ")"
                    : l.getNombre();
            boolean descuento = l.getTipo() == TipoConceptoSueldo.DESCUENTO_PORCENTAJE;
            String signo = descuento ? "−" : "";
            nombre = descuento ? conPorcentaje(nombre, l.getValor()) : nombre;
            lineas.add(renglon(nombre + (l.isAjustado() ? " *" : ""), signo + formato.pesos(l.getImporte())));
        }
        return Accion.Enviar.conMenu("🧮 <b>Sueldo de " + html(formato.mes(mes)) + "</b> (guardado)\n<pre>"
                + html(String.join("\n", lineas)) + "</pre>\n" + totales(c.getBruto(), c.getDescuentos(),
                        c.getNetoHaberes(), c.getNoRemunerativo(), c.getNetoTotal())
                + (c.getLineas().stream().anyMatch(CalculoSueldo.Linea::isAjustado)
                        ? "\n<i>* ajustado a mano</i>" : ""));
    }

    // --- Botones ---

    /** Si el botón es de la calculadora ("cg:202610:10_6_30"). */
    static boolean esBoton(String datos) {
        return datos != null && (datos.startsWith(GUARDAR + ":") || datos.startsWith(EDITAR + ":")
                || datos.startsWith(CANCELAR + ":"));
    }

    public List<Accion> boton(int mensajeId, String datos) {
        String[] partes = datos.split(":");
        YearMonth mes;
        Map<Long, BigDecimal> horas;
        try {
            mes = YearMonth.parse(partes[1].substring(0, 4) + "-" + partes[1].substring(4));
            horas = leerHoras(partes.length > 2 ? partes[2] : "");
        } catch (RuntimeException e) {
            return List.of(new Accion.Avisar("Botón inválido"));
        }
        return switch (partes[0]) {
            case GUARDAR -> guardar(mensajeId, mes, horas);
            case EDITAR -> List.of(new Accion.Editar(mensajeId, "✏️ <b>Para cambiar las horas</b>, contame de nuevo "
                    + "cuántas hiciste, como te salga (<i>10 al 50 y 30 nocturnas</i>).\n<i>" + html(nombresDeHoras())
                    + "</i>", Teclado.NINGUNO));
            default -> List.of(new Accion.Editar(mensajeId, "❌ <b>Cálculo cancelado</b>", Teclado.NINGUNO),
                    new Accion.Avisar("Cancelado"));
        };
    }

    private List<Accion> guardar(int mensajeId, YearMonth mes, Map<Long, BigDecimal> horas) {
        try {
            SueldoService.VistaCalculo v = sueldo.guardar(mes, horas, Map.of());
            return List.of(new Accion.Editar(mensajeId, "✅ <b>Cálculo guardado</b>\n" + tarjeta(v),
                    Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
        } catch (IllegalArgumentException e) {
            return List.of(new Accion.Avisar(e.getMessage()));
        }
    }

    // --- Textos ---

    String tarjeta(SueldoService.VistaCalculo v) {
        CalculadoraSueldo.Resultado r = v.resultado();
        List<String> horas = r.lineas().stream()
                .filter(l -> l.cantidad() != null && l.cantidad().signum() > 0)
                .map(l -> horas(l.cantidad()) + " " + l.nombre().toLowerCase(java.util.Locale.ROOT)).toList();
        StringBuilder texto = new StringBuilder("🧮 <b>Sueldo de " + html(formato.mes(v.periodo())) + "</b>\n");
        if (!horas.isEmpty()) {
            texto.append("⏱ ").append(html(String.join(" · ", horas))).append('\n');
        }
        texto.append(totales(r.bruto(), r.descuentos(), r.netoHaberes(), r.noRemunerativo(), r.netoTotal()));
        // La plata de cada descuento, no solo el total: así se compara renglón por renglón con el recibo.
        List<String> descuentos = r.lineas().stream()
                .filter(l -> l.tipo() == TipoConceptoSueldo.DESCUENTO_PORCENTAJE && l.importe().signum() != 0)
                .map(l -> renglon(conPorcentaje(l.nombre(), l.valor()), "−" + formato.pesos(l.importe()))).toList();
        if (!descuentos.isEmpty()) {
            texto.append("\nDescuentos:\n<pre>").append(html(String.join("\n", descuentos))).append("</pre>");
        }
        texto.append("\n<i>Escala vigente desde ").append(v.escalaDesde().getDayOfMonth()).append('/')
                .append(v.escalaDesde().getMonthValue()).append('/').append(v.escalaDesde().getYear())
                .append("</i>");
        return texto.toString();
    }

    private String totales(BigDecimal bruto, BigDecimal descuentos, BigDecimal netoHaberes,
            BigDecimal noRemunerativo, BigDecimal netoTotal) {
        return "<pre>" + html(punteado("Bruto", formato.pesos(bruto))) + "\n"
                + html(punteado("Descuentos", "−" + formato.pesos(descuentos))) + "\n"
                + html(punteado("Neto de haberes", formato.pesos(netoHaberes))) + "\n"
                + html(punteado("No remunerativo", formato.pesos(noRemunerativo))) + "\n"
                + html(punteado("💰 Neto total", formato.pesos(netoTotal))) + "</pre>";
    }

    private String ayuda() {
        return "🧮 <b>Calculadora de sueldo</b>\n¿Cuántas horas hiciste este mes? Contámelo como te salga, por "
                + "ejemplo: <i>este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas</i>.\n" + html(nombresDeHoras());
    }

    private String nombresDeHoras() {
        return "Los tipos de hora que tengo: " + sueldo.conceptosDeHoras().stream()
                .map(ConceptoSueldo::getNombre).map(n -> n.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.joining(", ")) + ".";
    }

    /** "Jubilación 11 %", "Sindicato 2,5 %": el porcentaje al lado de la plata que descuenta. */
    private static String conPorcentaje(String nombre, BigDecimal porcentaje) {
        return porcentaje == null ? nombre
                : nombre + " " + porcentaje.stripTrailingZeros().toPlainString().replace('.', ',') + " %";
    }

    private static String horas(BigDecimal cantidad) {
        return cantidad.stripTrailingZeros().toPlainString().replace('.', ',') + " h";
    }

    private Teclado.Boton boton(String texto, String accion, YearMonth mes, Map<Long, BigDecimal> horas) {
        String payload = sueldo.conceptosDeHoras().stream()
                .map(c -> horas.getOrDefault(c.getId(), BigDecimal.ZERO).stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("_"));
        return new Teclado.Boton(texto, accion + ":" + "%04d%02d".formatted(mes.getYear(), mes.getMonthValue())
                + ":" + payload);
    }

    private Map<Long, BigDecimal> leerHoras(String payload) {
        List<ConceptoSueldo> deHoras = sueldo.conceptosDeHoras();
        String[] cantidades = payload.isEmpty() ? new String[0] : payload.split("_");
        Map<Long, BigDecimal> horas = new HashMap<>();
        for (int i = 0; i < cantidades.length && i < deHoras.size(); i++) {
            horas.put(deHoras.get(i).getId(), new BigDecimal(cantidades[i]));
        }
        return horas;
    }

    private static String renglon(String nombre, String importe) {
        String corto = nombre.length() > ANCHO - importe.length() - 2
                ? nombre.substring(0, ANCHO - importe.length() - 3).stripTrailing() + "…" : nombre;
        return corto + " ".repeat(Math.max(2, ANCHO - corto.length() - importe.length())) + importe;
    }

    private static String punteado(String nombre, String importe) {
        int puntos = Math.max(2, ANCHO - nombre.length() - importe.length() - 2);
        return nombre + " " + ".".repeat(puntos) + " " + importe;
    }
}
