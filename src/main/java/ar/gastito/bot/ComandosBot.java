package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;

import java.util.ArrayList;
import java.util.List;

import ar.gastito.interpretacion.ParserHorasSueldo;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;

/**
 * Los comandos (los atajos) y las listas pedidas en lenguaje natural, que llevan al mismo código. Si los argumentos
 * de un atajo no se entienden sin IA, se interpretan como cualquier mensaje: nunca se le pide al usuario que
 * respete un formato.
 */
class ComandosBot {

    /** Interpretar un texto como cualquier mensaje (para los atajos que no se entienden sin IA). */
    interface Interpretar {
        List<Accion> interpretar(long chatId, String texto);
    }

    private final MovimientoService movimientos;
    private final FormatoMensajes formato;
    private final GraficoMes grafico;
    private final ComandosIngreso comandosIngreso;
    private final ResumenService resumenes;
    private final FormatoResumen formatoResumen;
    private final ConversacionJuntadas juntadas;
    private final ConversacionFijos fijos;
    private final ConversacionAltaFijo altaFijo;
    private final ConversacionCuotas cuotas;
    private final ComandosSueldo comandosSueldo;
    private final ComandoDisponible disponible;
    private final FormatoCostos formatoCostos;
    private final String urlDashboard;
    private final Interpretar interpretar;

    ComandosBot(MovimientoService movimientos, FormatoMensajes formato, GraficoMes grafico,
            ComandosIngreso comandosIngreso, ResumenService resumenes, FormatoResumen formatoResumen,
            ConversacionJuntadas juntadas, ConversacionFijos fijos, ConversacionAltaFijo altaFijo,
            ConversacionCuotas cuotas, ComandosSueldo comandosSueldo, ComandoDisponible disponible,
            FormatoCostos formatoCostos, String urlDashboard, Interpretar interpretar) {
        this.movimientos = movimientos;
        this.formato = formato;
        this.grafico = grafico;
        this.comandosIngreso = comandosIngreso;
        this.resumenes = resumenes;
        this.formatoResumen = formatoResumen;
        this.juntadas = juntadas;
        this.fijos = fijos;
        this.altaFijo = altaFijo;
        this.cuotas = cuotas;
        this.comandosSueldo = comandosSueldo;
        this.disponible = disponible;
        this.formatoCostos = formatoCostos;
        this.urlDashboard = urlDashboard;
        this.interpretar = interpretar;
    }

    /** "/hoy@gastito_bot extra" → "/hoy"; un botón del menú fijo → su comando. Null si no es un comando. */
    static String comando(String texto) {
        String delMenu = MenuFijo.comando(texto);
        if (delMenu != null) {
            return delMenu;
        }
        if (!texto.startsWith("/")) {
            return null;
        }
        return texto.split("\\s+")[0].split("@")[0].toLowerCase();
    }

    /**
     * Un comando, o lo mismo pedido en lenguaje natural ("¿qué fijos me faltan?" → /fijos).
     *
     * @param limpio el mensaje entero, con el comando ("/fijo seguro 32900 tarjeta día 4")
     */
    List<Accion> ejecutar(long chatId, String comando, String limpio) {
        String argumentos = limpio.replaceFirst("^/\\S+\\s*", "");
        return switch (comando) {
            case "/resumen" -> List.of(resumen(limpio));
            case "/juntadas" -> juntadas.abiertas();
            case "/fijo" -> argumentos.isBlank() ? List.of(Accion.Enviar.texto(ConversacionAltaFijo.preguntaInicial()))
                    : altaFijo.desdeComando(chatId, argumentos).<List<Accion>>map(List::of)
                            .orElseGet(() -> interpretar.interpretar(chatId, "agregá un gasto fijo: " + argumentos));
            case "/fijos" -> fijos.lista();
            case "/cuotas" -> cuotas.lista();
            case "/calcular" -> argumentos.isBlank() || ParserHorasSueldo.posicionales(argumentos).isPresent()
                    ? List.of(comandosSueldo.calcularDeComando(limpio))
                    : interpretar.interpretar(chatId, "este mes hice " + argumentos);
            case "/calculo" -> List.of(comandosSueldo.verCalculo());
            default -> {
                ClaseIngreso clase = ComandosIngreso.clase(comando);
                if (clase == null) {
                    yield List.of(responderComando(comando));
                }
                yield comandosIngreso.responder(clase, limpio).<List<Accion>>map(List::of)
                        .orElseGet(() -> interpretar.interpretar(chatId, ComandosIngreso.comoFrase(clase)
                                + argumentos));
            }
        };
    }

    /** Una lista pedida en lenguaje natural: lo mismo que su comando. */
    List<Accion> ver(long chatId, ValidadorInterpretacion.Lista lista) {
        String comando = comandoDe(lista);
        return ejecutar(chatId, comando.split(" ")[0], comando);
    }

    /** El comando que muestra lo mismo que una lista pedida en lenguaje natural. */
    static String comandoDe(ValidadorInterpretacion.Lista lista) {
        return switch (lista) {
            case FIJOS -> "/fijos";
            case DEUDAS -> "/juntadas";
            case CUOTAS -> "/cuotas";
            case COSTOS_IA -> "/costos";
            case RESUMEN_SEMANA -> "/resumen";
            case RESUMEN_MES -> "/resumen mes";
            case HOY -> "/hoy";
            case MES -> "/mes";
            case CALCULO -> "/calculo";
            case DASHBOARD -> "/dashboard";
            case AYUDA -> "/ayuda";
        };
    }

    // --- Resúmenes ---

    /** "/resumen": la semana en curso hasta hoy; "/resumen mes": el mes en curso hasta hoy. */
    private Accion resumen(String texto) {
        String[] palabras = texto.strip().split("\\s+");
        TipoResumen tipo = palabras.length > 1 && palabras[1].toLowerCase().startsWith("mes")
                ? TipoResumen.MENSUAL
                : TipoResumen.SEMANAL;
        ResumenService.Armado armado = resumenes.pedido(tipo);
        return Accion.Enviar.conMenu(formatoResumen.mensaje(armado, 0));
    }

    /** "Ver anteriores": los resúmenes que quedaron pendientes con la PC apagada. El botón se saca. */
    List<Accion> verAnteriores(int mensajeId, FormatoResumen.Anteriores anteriores) {
        List<ResumenService.Armado> armados = resumenes.anteriores(anteriores.tipo(), anteriores.desde(),
                anteriores.hasta());
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.QuitarBotones(mensajeId));
        if (armados.isEmpty()) {
            acciones.add(new Accion.Avisar("Ya los viste"));
            return acciones;
        }
        armados.forEach(a -> acciones.add(Accion.Enviar.texto(formatoResumen.mensaje(a, 0))));
        return acciones;
    }

    // --- Respuestas de texto ---

    /** Las respuestas a comandos van con el menú fijo, así queda siempre visible. */
    private Accion responderComando(String comando) {
        return Accion.Enviar.conMenu(switch (comando) {
            case "/start" -> TextosBot.BIENVENIDA;
            case "/ayuda", "/help" -> TextosBot.AYUDA;
            case "/hoy" -> hoy();
            case "/mes" -> mes();
            case "/costos" -> formatoCostos.comando();
            case "/disponible" -> disponible.responder();
            case "/dashboard" -> """
                    🖥 <b>Dashboard</b>: <a href="%1$s">%1$s</a>
                    Se abre desde el navegador de la PC donde corre Gastito.""".formatted(html(urlDashboard));
            default -> "No conozco ese comando. Mirá /ayuda";
        });
    }

    private String hoy() {
        MovimientoService.ResumenDia hoy = movimientos.hoy();
        if (hoy.gastos().isEmpty()) {
            return "Hoy no registraste gastos.";
        }
        StringBuilder sb = new StringBuilder("<b>Gastos de hoy</b>\n");
        for (Movimiento m : hoy.gastos()) {
            sb.append("• ").append(formato.linea(m)).append('\n');
        }
        return sb.append("\nTotal: <b>").append(formato.pesos(hoy.total())).append("</b>").toString();
    }

    private String mes() {
        MovimientoService.ResumenMes mes = movimientos.mesActual();
        String titulo = Character.toUpperCase(formato.mes(mes.mes()).charAt(0)) + formato.mes(mes.mes()).substring(1);
        if (mes.porCategoria().isEmpty()) {
            return titulo + ": todavía no hay gastos guardados.";
        }
        return "📅 <b>" + titulo + "</b>\nTotal: <b>" + formato.pesos(mes.total()) + "</b>\n\n"
                + grafico.barras(mes.porCategoria());
    }
}
