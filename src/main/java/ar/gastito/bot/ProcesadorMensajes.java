package ar.gastito.bot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.ia.ResumenUsoIa;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.Montos;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TotalPorCategoria;

/**
 * La conversación con el usuario, sin depender de la API de Telegram: recibe textos y datos de botones y
 * devuelve qué responder.
 */
@Component
public class ProcesadorMensajes {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorMensajes.class);

    static final String GUARDAR = "g";
    static final String EDITAR = "e";
    static final String CANCELAR = "c";

    static final String BIENVENIDA = """
            ¡Hola! Soy Gastito 👋
            Mandame tus gastos como si me escribieras por chat, por ejemplo:

            • kiosco 1500
            • 3700 en pan de pancho y papas, efectivo
            • pagué 12000 de nafta con la tarjeta

            Te muestro lo que entendí y lo guardás con un toque.
            Comandos: /hoy /mes /costos /ayuda""";

    static final String AYUDA = """
            Cómo cargar un gasto:
            • Lo más rápido: categoría y monto, en cualquier orden ("kiosco 1500", "1500 super"). Podés sumar el medio de pago ("super 8000 débito").
            • Si escribís algo más largo ("ayer 3700 en pan y papas con QR"), lo interpreta la IA.
            • Montos a la argentina: 1.500 o 1500,50.
            • Fechas: "ayer", "anteayer", "el sábado", "12/10".

            Antes de guardar te muestro lo que entendí con los botones Guardar, Editar y Cancelar.

            Comandos:
            /hoy – gastos de hoy y el total
            /mes – total del mes por categoría
            /costos – lo gastado en IA este mes
            /ayuda – este mensaje""";

    private final InterpretadorService interpretador;
    private final MovimientoService movimientos;
    private final RegistroUsoIa usoIa;
    private final FormatoMensajes formato;
    private final Clock clock;

    /** Por chat: un gasto al que solo le falta el monto, esperando que el usuario lo mande. */
    private final Map<Long, EsperaMonto> esperandoMonto = new ConcurrentHashMap<>();

    private record EsperaMonto(MovimientoInterpretado sinMonto, Long usoIaId, String textoOriginal) {
    }

    /** Resultado de tocar un botón: el nuevo texto del mensaje (sin botones) y un aviso corto opcional. */
    public record ResultadoBoton(String textoMensaje, String aviso) {
    }

    public ProcesadorMensajes(InterpretadorService interpretador, MovimientoService movimientos,
            RegistroUsoIa usoIa, FormatoMensajes formato, Clock clock) {
        this.interpretador = interpretador;
        this.movimientos = movimientos;
        this.usoIa = usoIa;
        this.formato = formato;
        this.clock = clock;
    }

    public Respuesta procesarTexto(long chatId, String texto) {
        String limpio = texto == null ? "" : texto.trim();
        if (limpio.startsWith("/")) {
            esperandoMonto.remove(chatId);
            return comando(limpio);
        }

        EsperaMonto espera = esperandoMonto.remove(chatId);
        if (espera != null) {
            Optional<BigDecimal> monto = Montos.parsear(limpio);
            if (monto.isPresent()) {
                return proponer(espera.sinMonto().conMonto(monto.get()), espera.usoIaId(),
                        espera.textoOriginal() + " | monto: " + limpio);
            }
            // No mandó un monto: se descarta lo anterior y se interpreta como un mensaje nuevo.
        }

        InterpretadorService.Resultado resultado;
        try {
            resultado = interpretador.interpretar(limpio);
        } catch (IaNoDisponibleException e) {
            return Respuesta.texto("""
                    No pude interpretar el mensaje: %s.
                    Mientras tanto podés usar el formato simple, por ejemplo: kiosco 1500""".formatted(e.getMessage()));
        }

        return switch (resultado) {
            case InterpretadorService.Interpretado i -> proponer(i.movimiento(), i.usoIaId(), limpio);
            case InterpretadorService.FaltaMonto f -> {
                esperandoMonto.put(chatId, new EsperaMonto(f.sinMonto(), f.usoIaId(), limpio));
                yield Respuesta.texto("¿Cuánto fue?\nEntendí: " + formato.linea(f.sinMonto())
                        + "\nMandame solo el monto, por ejemplo: 3700");
            }
            case InterpretadorService.NoEsGasto n -> Respuesta.texto("""
                    No encontré un gasto en el mensaje 🤔
                    Probá con algo como "kiosco 1500" o mirá /ayuda""");
        };
    }

    public ResultadoBoton procesarBoton(String datos) {
        String[] partes = datos == null ? new String[0] : datos.split(":");
        Long id = partes.length == 2 ? parsearId(partes[1]) : null;
        if (id == null) {
            return new ResultadoBoton(null, "Botón inválido");
        }
        try {
            return switch (partes[0]) {
                case GUARDAR -> {
                    MovimientoService.Confirmacion c = movimientos.confirmar(id);
                    Movimiento m = c.movimiento();
                    yield new ResultadoBoton("✅ Guardado\n" + formato.linea(m)
                            + "\nEn " + formato.mes(YearMonth.from(m.getFechaHora())) + " llevás "
                            + formato.pesos(c.totalMesCategoria()) + " en " + m.getCategoria().getNombre() + ".",
                            "Guardado");
                }
                case CANCELAR -> new ResultadoBoton("❌ Cancelado\n" + formato.linea(movimientos.cancelar(id)),
                        "Cancelado");
                case EDITAR -> new ResultadoBoton("✏️ Descartado para corregir\n"
                        + formato.linea(movimientos.cancelar(id))
                        + "\nMandame el gasto de nuevo con los cambios.", null);
                default -> new ResultadoBoton(null, "Botón inválido");
            };
        } catch (MovimientoNoPendienteException e) {
            Movimiento m = e.getMovimiento();
            String estado = m.getEstado() == EstadoMovimiento.CONFIRMADO ? "✅ Ya estaba guardado" : "❌ Ya estaba cancelado";
            return new ResultadoBoton(estado + "\n" + formato.linea(m), null);
        } catch (NoSuchElementException e) {
            log.warn("Botón para un movimiento que no existe: {}", datos);
            return new ResultadoBoton(null, "No encontré ese gasto");
        }
    }

    private Respuesta proponer(MovimientoInterpretado interpretado, Long usoIaId, String textoOriginal) {
        Movimiento pendiente = movimientos.crearPendiente(interpretado, Origen.TEXTO, textoOriginal);
        if (usoIaId != null) {
            usoIa.asociarMovimiento(usoIaId, pendiente.getId());
        }
        long id = pendiente.getId();
        return new Respuesta(formato.linea(interpretado) + "\n¿Lo guardo así?", List.of(
                new Respuesta.Boton("Guardar", GUARDAR + ":" + id),
                new Respuesta.Boton("Editar", EDITAR + ":" + id),
                new Respuesta.Boton("Cancelar", CANCELAR + ":" + id)));
    }

    private Respuesta comando(String texto) {
        // "/hoy@gastito_bot extra" → "/hoy"
        String comando = texto.split("\\s+")[0].split("@")[0].toLowerCase();
        return switch (comando) {
            case "/start" -> Respuesta.texto(BIENVENIDA);
            case "/ayuda", "/help" -> Respuesta.texto(AYUDA);
            case "/hoy" -> Respuesta.texto(hoy());
            case "/mes" -> Respuesta.texto(mes());
            case "/costos" -> Respuesta.texto(costos());
            default -> Respuesta.texto("No conozco ese comando. Mirá /ayuda");
        };
    }

    private String hoy() {
        MovimientoService.ResumenDia hoy = movimientos.hoy();
        if (hoy.gastos().isEmpty()) {
            return "Hoy no registraste gastos.";
        }
        StringBuilder sb = new StringBuilder("Gastos de hoy:\n");
        for (Movimiento m : hoy.gastos()) {
            sb.append("• ").append(formato.linea(m)).append('\n');
        }
        return sb.append("\nTotal: ").append(formato.pesos(hoy.total())).toString();
    }

    private String mes() {
        MovimientoService.ResumenMes mes = movimientos.mesActual();
        String titulo = Character.toUpperCase(formato.mes(mes.mes()).charAt(0)) + formato.mes(mes.mes()).substring(1);
        if (mes.porCategoria().isEmpty()) {
            return titulo + ": todavía no hay gastos guardados.";
        }
        StringBuilder sb = new StringBuilder(titulo).append(":\n");
        for (TotalPorCategoria t : mes.porCategoria()) {
            sb.append("• ").append(t.categoria()).append(": ").append(formato.pesos(t.total())).append('\n');
        }
        return sb.append("\nTotal: ").append(formato.pesos(mes.total())).toString();
    }

    private String costos() {
        ResumenUsoIa r = usoIa.resumenMesActual();
        String mes = formato.mes(YearMonth.now(clock));
        if (r.llamadas() == 0) {
            return "En " + mes + " todavía no se usó la IA.";
        }
        return """
                Uso de la IA en %s:
                • Llamadas: %d
                • Tokens: %d de entrada, %d de salida
                • Costo: %s""".formatted(mes, r.llamadas(), r.tokensEntrada(), r.tokensSalida(),
                formato.dolares(r.costoUsd()));
    }

    private static Long parsearId(String texto) {
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
