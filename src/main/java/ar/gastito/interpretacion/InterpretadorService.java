package ar.gastito.interpretacion;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;

/**
 * Interpreta un mensaje de texto, de lo más barato a lo más caro:
 * <ol>
 * <li>Parser simple ("kiosco 1500").</li>
 * <li>Historial: el mensaje describe algo que el usuario ya confirmó siempre en la misma categoría.</li>
 * <li>IA, y después el historial corrige la categoría si la descripción ya es conocida.</li>
 * </ol>
 */
@Service
public class InterpretadorService {

    static final String PROMPT = "interpretar-gasto";

    private final CatalogoService catalogos;
    private final ParserSimple parser;
    private final ValidadorInterpretacion validador;
    private final HistorialCategorias historial;
    private final ResolvedorFechas fechas;
    private final MovimientoService movimientos;
    private final ClienteIa ia;
    private final Prompts prompts;
    private final Clock clock;

    public InterpretadorService(CatalogoService catalogos, ParserSimple parser, ValidadorInterpretacion validador,
            HistorialCategorias historial, ResolvedorFechas fechas, MovimientoService movimientos, ClienteIa ia,
            Prompts prompts, Clock clock) {
        this.catalogos = catalogos;
        this.parser = parser;
        this.validador = validador;
        this.historial = historial;
        this.fechas = fechas;
        this.movimientos = movimientos;
        this.ia = ia;
        this.prompts = prompts;
        this.clock = clock;
    }

    public sealed interface Resultado {
    }

    /**
     * El movimiento puede venir sin monto o sin categoría elegida (con candidatas): se completa en la tarjeta.
     *
     * @param usoIaId registro en uso_ia, o null si se resolvió sin IA
     * @param juntada si pagó por un grupo (el monto es el total), o null
     * @param cuotas  si es una compra en cuotas (el monto es el total), o null
     */
    public record Interpretado(MovimientoInterpretado movimiento, Confianza confianza, Long usoIaId,
            PedidoJuntada juntada, PedidoCuotas cuotas) implements Resultado {

        public Interpretado(MovimientoInterpretado movimiento, Confianza confianza, Long usoIaId) {
            this(movimiento, confianza, usoIaId, null, null);
        }
    }

    /** "Pablo me pasó 8000": una devolución de una juntada. Lo que dijo, ya controlado. */
    public record Devolucion(ValidadorInterpretacion.Devolucion datos, Long usoIaId) implements Resultado {
    }

    /** "Agregá un fijo: el seguro, 32900 con tarjeta el 4": el alta de un gasto fijo. */
    public record AltaFijo(PedidoFijo pedido, Long usoIaId) implements Resultado {
    }

    public record NoEsGasto() implements Resultado {
    }

    /**
     * Una pregunta sobre sus gastos ("¿cuánto gasté en kiosco?").
     *
     * @param usoIaId si la IA intervino al clasificarla, el registro en uso_ia; null si la reconoció Java
     */
    public record Pregunta(String texto, Long usoIaId) implements Resultado {
    }

    /**
     * No está claro qué quiere: se le pregunta con botones entre las opciones. Si es un gasto, ya está interpretado;
     * para las otras intenciones se vuelve a validar la misma respuesta de la IA ({@link #reinterpretar}), sin otra
     * llamada.
     *
     * @param ia la respuesta de la IA, o null si no intervino
     */
    public record Dudoso(Interpretado comoGasto, List<Intencion> opciones, InterpretacionIa ia, Long usoIaId)
            implements Resultado {

        public Dudoso(Interpretado comoGasto) {
            this(comoGasto, ValidadorInterpretacion.OPCIONES_POR_DEFECTO, null, comoGasto.usoIaId());
        }
    }

    /**
     * Cualquier otro pedido (un ingreso, corregir o borrar lo último, una lista, el cálculo del sueldo…): lo que
     * entendió el validador, para que lo resuelva el bot.
     *
     * @param usoIaId registro en uso_ia, o null si se reconoció sin IA
     */
    public record Orden(ValidadorInterpretacion.Resultado orden, Long usoIaId) implements Resultado {
    }

    /** Un mensaje escrito. */
    public Resultado interpretar(String texto) {
        return interpretar(texto, PropositoIa.TEXTO);
    }

    /**
     * @param proposito con qué propósito queda registrada en uso_ia la llamada a la IA, si hace falta
     *                  (TEXTO para un mensaje escrito, AUDIO para la transcripción de un audio)
     * @throws ar.gastito.ia.IaNoDisponibleException si hacía falta la IA y no se pudo usar
     */
    public Resultado interpretar(String texto, PropositoIa proposito) {
        // 0. Un pedido de lista obvio ("¿qué fijos me faltan?") o una pregunta obvia, sin monto: sin IA.
        Optional<ValidadorInterpretacion.Lista> lista = ClasificadorIntencion.pedidoDeLista(texto);
        if (lista.isPresent()) {
            return new Orden(new ValidadorInterpretacion.Ver(lista.get()), null);
        }
        if (ClasificadorIntencion.esPreguntaObvia(texto)) {
            return new Pregunta(texto, null);
        }
        Catalogo catalogo = catalogos.cargar();
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        // 1. Parser simple: "kiosco 1500".
        Optional<ParserSimple.Resultado> simple = parser.parsear(texto, catalogo);
        if (simple.isPresent()) {
            ParserSimple.Resultado r = simple.get();
            return new Interpretado(MovimientoInterpretado.conCategoria(r.monto(), r.descripcion(), r.categoria(),
                    r.medioPago(), cuenta(r.medioPago(), r.cuenta()), tarjeta(r.medioPago(), catalogo), ahora),
                    Confianza.ALTA, null);
        }

        // 2. Historial: el resto del mensaje coincide con gastos que el usuario ya confirmó en una sola categoría.
        Optional<ParserSimple.Separado> separado = parser.separar(texto, catalogo);
        if (separado.isPresent()) {
            ParserSimple.Separado s = separado.get();
            Optional<HistorialCategorias.Aprendido> aprendido = historial.buscar(s.resto(), catalogo);
            if (aprendido.isPresent()) {
                return new Interpretado(MovimientoInterpretado.conCategoria(s.monto(), aprendido.get().descripcion(),
                        aprendido.get().categoria(), s.medioPago(), cuenta(s.medioPago(), s.cuenta()),
                        tarjeta(s.medioPago(), catalogo), fechas.resolver(s.fecha(), "", ahora)),
                        Confianza.ALTA, null);
            }
        }

        // 3. IA, con las descripciones de las categorías y las últimas confirmaciones como ejemplos.
        String instrucciones = prompts.cargar(PROMPT, Map.of(
                "categorias", ContextoIa.categorias(catalogo.categorias()),
                "mediosPago", ContextoIa.lista(catalogo.mediosPago()),
                "cuentas", ContextoIa.lista(catalogo.cuentas()),
                "ejemplos", ContextoIa.ejemplos(movimientos.ultimasConfirmaciones()),
                "hoy", ContextoIa.hoy(ahora)));
        ClienteIa.Respuesta<InterpretacionIa> respuesta =
                ia.extraer(instrucciones, texto, InterpretacionIa.class, proposito);

        return resultado(validador.validar(respuesta.valor(), catalogo, ahora), respuesta.valor(), texto, catalogo,
                respuesta.usoIaId());
    }

    /**
     * El usuario eligió qué quería entre las opciones de un {@link Dudoso}: se valida la misma respuesta de la IA
     * con esa intención (sin llamarla de nuevo).
     */
    public Resultado reinterpretar(Dudoso dudoso, Intencion elegida, String texto) {
        if (dudoso.ia() == null) {
            return elegida == Intencion.GASTO ? dudoso.comoGasto() : new Pregunta(texto, dudoso.usoIaId());
        }
        Catalogo catalogo = catalogos.cargar();
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        InterpretacionIa ia = dudoso.ia().conIntencion(elegida);
        return resultado(validador.validar(ia, catalogo, ahora), ia, texto, catalogo, dudoso.usoIaId());
    }

    private Resultado resultado(ValidadorInterpretacion.Resultado validado, InterpretacionIa ia, String texto,
            Catalogo catalogo, Long usoIaId) {
        return switch (validado) {
            case ValidadorInterpretacion.Valida v -> conHistorial(v, catalogo, usoIaId);
            case ValidadorInterpretacion.NoEsGasto n -> new NoEsGasto();
            case ValidadorInterpretacion.Pregunta p -> new Pregunta(texto, usoIaId);
            case ValidadorInterpretacion.Dudosa d ->
                    new Dudoso(conHistorial(d.comoGasto(), catalogo, usoIaId), d.opciones(), ia, usoIaId);
            case ValidadorInterpretacion.Devolucion d -> new Devolucion(d, usoIaId);
            case ValidadorInterpretacion.AltaFijo a -> new AltaFijo(a.pedido(), usoIaId);
            default -> new Orden(validado, usoIaId);
        };
    }

    /** 4. Lo que el usuario ya decidió para esa descripción manda sobre lo que dijo la IA. */
    private Interpretado conHistorial(ValidadorInterpretacion.Valida v, Catalogo catalogo, Long usoIaId) {
        Optional<HistorialCategorias.Aprendido> aprendido = historial.buscar(v.movimiento().descripcion(), catalogo);
        return aprendido.isPresent()
                ? new Interpretado(v.movimiento().conCategoriaElegida(aprendido.get().categoria()), Confianza.ALTA,
                        usoIaId, v.juntada(), v.cuotas())
                : new Interpretado(v.movimiento(), v.confianza(), usoIaId, v.juntada(), v.cuotas());
    }

    private static Cuenta cuenta(MedioPago medio, Cuenta cuenta) {
        return medio != null && medio.esTarjetaDeCredito() ? null : cuenta;
    }

    private static TarjetaCredito tarjeta(MedioPago medio, Catalogo catalogo) {
        return medio != null && medio.esTarjetaDeCredito() ? catalogo.tarjetaCredito().orElse(null) : null;
    }
}
