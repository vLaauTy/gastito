package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.sueldo.escala.HojaEscala;
import ar.gastito.sueldo.escala.ImportacionEscala;
import ar.gastito.sueldo.escala.ImportacionEscala.Clase;
import ar.gastito.sueldo.escala.ImportacionEscala.MesPropuesto;
import ar.gastito.sueldo.escala.ImportacionEscala.Valor;
import ar.gastito.sueldo.escala.ImportacionesPendientes;
import ar.gastito.sueldo.escala.ImportacionesPendientes.Pendiente;
import ar.gastito.sueldo.escala.LectorExcelEscala;
import ar.gastito.sueldo.escala.LectorFotoEscala;
import ar.gastito.sueldo.escala.MesHoja;
import ar.gastito.ticket.ImagenInvalidaException;

/**
 * La escala de sueldo desde la hoja del sindicato, en el bot: una foto (la lee Haiku una vez) o un Excel (sin IA).
 * Antes de guardar muestra un resumen por mes, con los cambios contra la escala anterior y el control con "Valores
 * netos", y botones para guardar todas, elegir meses o cancelar. La hoja leída espera en
 * {@link ImportacionesPendientes}; los botones llevan su id ("esc:g:3").
 */
@Component
public class ConversacionEscalas {

    private static final Logger log = LoggerFactory.getLogger(ConversacionEscalas.class);

    static final String PREFIJO = "esc";
    static final String GUARDAR = "g";
    static final String ELEGIR = "m";
    static final String ALTERNAR = "t";
    static final String VOLVER = "v";
    static final String CANCELAR = "x";
    static final String FIJAR_MES = "f";
    static final long TAMANIO_MAXIMO_EXCEL = 5L * 1024 * 1024;

    /** El texto que acompaña la foto dice que es la hoja de la escala: se lee directo, sin pasar por el ticket. */
    private static final Pattern MENCIONA_ESCALA =
            Pattern.compile("(?i)\\b(escalas?|paritarias?|sindicato|recomposici[oó]n)\\b");

    private final LectorFotoEscala fotos;
    private final LectorExcelEscala excels;
    private final ImportacionEscala importacion;
    private final ImportacionesPendientes pendientes;
    private final FormatoMensajes formato;
    private final Clock clock;

    public ConversacionEscalas(LectorFotoEscala fotos, LectorExcelEscala excels, ImportacionEscala importacion,
            ImportacionesPendientes pendientes, FormatoMensajes formato, Clock clock) {
        this.fotos = fotos;
        this.excels = excels;
        this.importacion = importacion;
        this.pendientes = pendientes;
        this.formato = formato;
        this.clock = clock;
    }

    static boolean mencionaEscala(String texto) {
        return texto != null && MENCIONA_ESCALA.matcher(texto).find();
    }

    static boolean esBoton(String datos) {
        return datos != null && datos.startsWith(PREFIJO + ":");
    }

    // --- Lectura ---

    /** Una foto que el usuario dijo que es la hoja de la escala. */
    public Accion leerFoto(FotoRecibida foto) {
        byte[] bytes;
        try {
            bytes = foto.descarga().descargar();
        } catch (IOException e) {
            log.warn("No se pudo descargar la foto de Telegram ({})", e.getClass().getSimpleName());
            return Accion.Enviar.texto("No pude descargar la foto de Telegram 😕 Probá mandarla de nuevo.");
        }
        return leerFoto(bytes, foto.texto());
    }

    /** Una foto ya descargada (también la que el ticket reconoció como la hoja de la escala). */
    public Accion leerFoto(byte[] bytes, String texto) {
        LectorFotoEscala.Resultado resultado;
        try {
            resultado = fotos.leer(bytes, texto);
        } catch (ImagenInvalidaException e) {
            return Accion.Enviar.texto("""
                    No pude abrir esa imagen 😕
                    Mandala como foto (no como archivo), o en JPG o PNG.""");
        } catch (IaNoDisponibleException e) {
            return Accion.Enviar.texto("""
                    No pude leer la hoja: %s.
                    Mandámela de nuevo en un rato; no guardé nada.""".formatted(html(e.getMessage())));
        }
        return switch (resultado) {
            case LectorFotoEscala.Leida l -> proponer(l.hoja());
            case LectorFotoEscala.NoEsEscala n -> Accion.Enviar.texto("""
                    Esa foto no parece la hoja de la escala 🤔
                    Mandame la foto de la hoja del sindicato, con los meses arriba, o el Excel.""");
            case LectorFotoEscala.Ilegible i -> Accion.Enviar.texto("""
                    No pude leer bien la hoja 📷 No guardé nada.
                    Sacale una foto derecha, con buena luz, entera y que se vean los meses de arriba. O mandame el Excel.""");
        };
    }

    public Accion leerExcel(ExcelRecibido excel) {
        if (excel.bytes() != null && excel.bytes() > TAMANIO_MAXIMO_EXCEL) {
            return Accion.Enviar.texto("El Excel pesa más de 5 MB 🙏 Mandame uno más liviano.");
        }
        byte[] bytes;
        try {
            bytes = excel.descarga().descargar();
        } catch (IOException e) {
            log.warn("No se pudo descargar el Excel de Telegram ({})", e.getClass().getSimpleName());
            return Accion.Enviar.texto("No pude descargar el archivo de Telegram 😕 Probá mandarlo de nuevo.");
        }
        try {
            return proponer(excels.leer(bytes));
        } catch (IllegalArgumentException e) {
            return Accion.Enviar.texto("😕 " + html(e.getMessage()) + ". No guardé nada.");
        }
    }

    Accion proponer(HojaEscala hoja) {
        if (hoja.faltaElMes()) {
            Pendiente p = pendientes.agregar(hoja, null);
            YearMonth actual = YearMonth.now(clock);
            List<Teclado.Boton> meses = List.of(actual.minusMonths(1), actual, actual.plusMonths(1)).stream()
                    .map(m -> boton(MesHoja.corto(m), FIJAR_MES, p.id(), m)).toList();
            return Accion.Enviar.conBotones("📄 Leí una escala de un solo mes, pero la planilla no dice de qué mes "
                    + "es. ¿De cuál es?", Teclado.constructor().fila(meses.toArray(Teclado.Boton[]::new))
                            .fila(boton("❌ Cancelar", CANCELAR, p.id(), null)).build());
        }
        ImportacionEscala.Propuesta propuesta;
        try {
            propuesta = importacion.proponer(hoja);
        } catch (IllegalArgumentException e) {
            return Accion.Enviar.texto("😕 " + html(e.getMessage()) + "\nNo guardé nada.");
        }
        Pendiente p = pendientes.agregar(hoja, propuesta);
        return Accion.Enviar.conBotones(tarjeta(p), teclado(p));
    }

    // --- Botones ---

    public List<Accion> boton(int mensajeId, String datos) {
        String[] partes = datos.split(":");
        Long id = partes.length >= 3 ? numero(partes[2]) : null;
        YearMonth mes = partes.length >= 4 ? mes(partes[3]) : null;
        if (id == null || (partes.length >= 4 && mes == null)) {
            return List.of(new Accion.Avisar("Botón inválido"));
        }
        Optional<Pendiente> encontrada = pendientes.buscar(id);
        if (encontrada.isEmpty()) {
            return List.of(new Accion.QuitarBotones(mensajeId),
                    new Accion.Avisar("Esa hoja ya no está: mandámela de nuevo"));
        }
        Pendiente p = encontrada.get();
        return switch (partes[1]) {
            case CANCELAR -> {
                pendientes.quitar(id);
                yield List.of(new Accion.Editar(mensajeId, "❌ <b>Escala descartada</b>: no guardé nada.",
                        Teclado.NINGUNO), new Accion.Avisar("Cancelado"));
            }
            case FIJAR_MES -> fijarMes(mensajeId, p, mes);
            case ELEGIR -> p.propuesta() == null ? invalido()
                    : List.of(new Accion.Editar(mensajeId, tarjeta(p), tecladoDeMeses(p)));
            case ALTERNAR -> p.propuesta() == null || mes == null ? invalido()
                    : List.of(new Accion.Editar(mensajeId, tarjeta(p), tecladoDeMeses(pendientes.alternar(p, mes))));
            case VOLVER -> p.propuesta() == null ? invalido()
                    : List.of(new Accion.Editar(mensajeId, tarjeta(p), teclado(p)));
            case GUARDAR -> guardar(mensajeId, p);
            default -> invalido();
        };
    }

    private List<Accion> fijarMes(int mensajeId, Pendiente p, YearMonth mes) {
        if (mes == null) {
            return invalido();
        }
        HojaEscala hoja = p.hoja().conMes(mes);
        try {
            Pendiente con = pendientes.conPropuesta(p, hoja, importacion.proponer(hoja));
            return List.of(new Accion.Editar(mensajeId, tarjeta(con), teclado(con)));
        } catch (IllegalArgumentException e) {
            pendientes.quitar(p.id());
            return List.of(new Accion.Editar(mensajeId, "😕 " + html(e.getMessage()) + "\nNo guardé nada.",
                    Teclado.NINGUNO));
        }
    }

    private List<Accion> guardar(int mensajeId, Pendiente p) {
        if (p.propuesta() == null) {
            return invalido();
        }
        if (p.elegidos().isEmpty()) {
            return List.of(new Accion.Avisar("Elegí al menos un mes"));
        }
        List<ImportacionEscala.Guardada> guardadas;
        try {
            guardadas = importacion.guardar(p.propuesta(), p.elegidos());
        } catch (IllegalArgumentException e) {
            return List.of(new Accion.Avisar(e.getMessage()));
        }
        pendientes.quitar(p.id());
        String meses = guardadas.stream()
                .map(g -> MesHoja.corto(g.mes()) + (g.reemplazada() ? " (reemplazada)" : ""))
                .collect(Collectors.joining(", "));
        return List.of(new Accion.Editar(mensajeId, "✅ <b>" + (guardadas.size() == 1 ? "Escala guardada"
                : "Escalas guardadas") + "</b>: " + html(meses) + "\n" + resumenMeses(p.propuesta()),
                Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
    }

    private static List<Accion> invalido() {
        return List.of(new Accion.Avisar("Botón inválido"));
    }

    // --- Textos ---

    String tarjeta(Pendiente p) {
        ImportacionEscala.Propuesta propuesta = p.propuesta();
        StringBuilder texto = new StringBuilder("📄 <b>Escalas desde la hoja del sindicato</b>\n");
        texto.append(resumenMeses(propuesta));
        for (MesPropuesto m : propuesta.meses()) {
            if (m.reemplaza()) {
                List<Valor> cambian = m.leidos().stream().filter(Valor::cambio).toList();
                texto.append("\n🔁 <b>").append(MesHoja.corto(m.mes())).append("</b> ya tiene una escala: ")
                        .append(cambian.isEmpty() ? "la hoja no cambia nada."
                                : "se reemplaza. Cambia " + html(cambian.stream().map(this::cambio)
                                        .collect(Collectors.joining(" · "))) + ".");
            }
        }
        List<String> mantenidos = propuesta.meses().stream().map(m -> mantenidos(m.mantenidos())).distinct().toList();
        if (mantenidos.size() == 1 && !mantenidos.getFirst().isEmpty()) {
            texto.append("\nSe mantienen: ").append(html(mantenidos.getFirst()));
        } else if (mantenidos.size() > 1) {
            for (MesPropuesto m : propuesta.meses()) {
                if (!m.mantenidos().isEmpty()) {
                    texto.append("\nSe mantienen en ").append(MesHoja.corto(m.mes())).append(": ")
                            .append(html(mantenidos(m.mantenidos())));
                }
            }
        }
        if (!propuesta.ignoradas().isEmpty()) {
            texto.append("\n<i>No uso: ").append(html(String.join(", ", propuesta.ignoradas()))).append("</i>");
        }
        return texto.toString();
    }

    /** Un renglón por mes: lo que trae la hoja con su cambio y el resultado del control. */
    private String resumenMeses(ImportacionEscala.Propuesta propuesta) {
        StringBuilder texto = new StringBuilder();
        for (MesPropuesto m : propuesta.meses()) {
            texto.append("<b>").append(MesHoja.corto(m.mes())).append("</b>  ")
                    .append(html(m.leidos().stream().map(this::leido).collect(Collectors.joining(" · "))))
                    .append(' ').append(control(m)).append('\n');
        }
        return texto.toString().stripTrailing();
    }

    private String control(MesPropuesto m) {
        if (m.control() == null) {
            return "<i>(sin «Valores netos» para controlar)</i>";
        }
        if (m.control().ok()) {
            return "✅ control";
        }
        return "⚠️ Con tus descuentos da " + formato.pesos(m.control().calculado()) + " y la hoja dice "
                + formato.pesos(m.control().hoja()) + ": revisá los porcentajes";
    }

    private String leido(Valor v) {
        String texto = v.nombre() + " " + valor(v.clase(), v.ahora());
        if (v.variacion() != null && v.variacion().signum() != 0) {
            texto += " (" + (v.variacion().signum() > 0 ? "+" : "") + porcentaje(v.variacion())
                    + "%)";
        }
        return texto;
    }

    private String cambio(Valor v) {
        return v.nombre() + " " + (v.antes() == null ? "—" : valor(v.clase(), v.antes())) + " → "
                + valor(v.clase(), v.ahora());
    }

    /** "CCT planta nuclear $98.985, descuentos 11 + 3 + 3 + 3 %". */
    private String mantenidos(List<Valor> valores) {
        List<String> partes = new ArrayList<>(valores.stream().filter(v -> v.clase() != Clase.PORCENTAJE)
                .map(v -> v.nombre() + " " + valor(v.clase(), v.ahora())).toList());
        List<String> porcentajes = valores.stream().filter(v -> v.clase() == Clase.PORCENTAJE)
                .map(v -> porcentaje(v.ahora())).toList();
        if (!porcentajes.isEmpty()) {
            partes.add("descuentos " + String.join(" + ", porcentajes) + " %");
        }
        return String.join(", ", partes);
    }

    private String valor(Clase clase, BigDecimal valor) {
        return clase == Clase.PORCENTAJE ? porcentaje(valor) + " %" : formato.pesos(valor);
    }

    private static String porcentaje(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private Teclado teclado(Pendiente p) {
        List<MesPropuesto> meses = p.propuesta().meses();
        boolean todas = p.elegidos().size() == meses.size();
        boolean reemplaza = meses.stream().anyMatch(m -> m.reemplaza() && p.elegidos().contains(m.mes()));
        String guardar = !todas ? "✅ Guardar elegidas (" + p.elegidos().size() + ")"
                : reemplaza ? "✅ Guardar y reemplazar" : meses.size() == 1 ? "✅ Guardar" : "✅ Guardar todas";
        Teclado.Constructor teclado = Teclado.constructor().fila(boton(guardar, GUARDAR, p.id(), null));
        if (meses.size() > 1) {
            teclado.fila(boton("📅 Elegir meses", ELEGIR, p.id(), null), boton("❌ Cancelar", CANCELAR, p.id(), null));
        } else {
            teclado.fila(boton("❌ Cancelar", CANCELAR, p.id(), null));
        }
        return teclado.build();
    }

    private Teclado tecladoDeMeses(Pendiente p) {
        List<Teclado.Boton> meses = p.propuesta().meses().stream()
                .map(m -> boton((p.elegidos().contains(m.mes()) ? "☑️ " : "⬜ ") + MesHoja.corto(m.mes()), ALTERNAR,
                        p.id(), m.mes()))
                .toList();
        return Teclado.constructor().grilla(meses, 3)
                .fila(boton("✅ Guardar elegidas (" + p.elegidos().size() + ")", GUARDAR, p.id(), null),
                        boton("↩️ Volver", VOLVER, p.id(), null))
                .build();
    }

    private static Teclado.Boton boton(String texto, String accion, long id, YearMonth mes) {
        return new Teclado.Boton(texto, PREFIJO + ":" + accion + ":" + id
                + (mes == null ? "" : ":%04d%02d".formatted(mes.getYear(), mes.getMonthValue())));
    }

    private static Long numero(String texto) {
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static YearMonth mes(String texto) {
        try {
            return YearMonth.of(Integer.parseInt(texto.substring(0, 4)), Integer.parseInt(texto.substring(4)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
