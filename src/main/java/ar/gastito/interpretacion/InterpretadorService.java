package ar.gastito.interpretacion;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
     */
    public record Interpretado(MovimientoInterpretado movimiento, Confianza confianza, Long usoIaId)
            implements Resultado {
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

    /** Puede ser un gasto o una pregunta: se le pregunta al usuario. Si es un gasto, ya está interpretado. */
    public record Dudoso(Interpretado comoGasto) implements Resultado {
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
        // 0. Una pregunta obvia, sin monto: no hace falta la IA para saber que no es un gasto.
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

        return switch (validador.validar(respuesta.valor(), catalogo, ahora)) {
            case ValidadorInterpretacion.Valida v -> conHistorial(v, catalogo, respuesta.usoIaId());
            case ValidadorInterpretacion.NoEsGasto n -> new NoEsGasto();
            case ValidadorInterpretacion.Pregunta p -> new Pregunta(texto, respuesta.usoIaId());
            case ValidadorInterpretacion.Dudosa d ->
                    new Dudoso(conHistorial(d.comoGasto(), catalogo, respuesta.usoIaId()));
        };
    }

    /** 4. Lo que el usuario ya decidió para esa descripción manda sobre lo que dijo la IA. */
    private Interpretado conHistorial(ValidadorInterpretacion.Valida v, Catalogo catalogo, Long usoIaId) {
        Optional<HistorialCategorias.Aprendido> aprendido = historial.buscar(v.movimiento().descripcion(), catalogo);
        return aprendido.isPresent()
                ? new Interpretado(v.movimiento().conCategoriaElegida(aprendido.get().categoria()), Confianza.ALTA,
                        usoIaId)
                : new Interpretado(v.movimiento(), v.confianza(), usoIaId);
    }

    private static Cuenta cuenta(MedioPago medio, Cuenta cuenta) {
        return medio != null && medio.esTarjetaDeCredito() ? null : cuenta;
    }

    private static TarjetaCredito tarjeta(MedioPago medio, Catalogo catalogo) {
        return medio != null && medio.esTarjetaDeCredito() ? catalogo.tarjetaCredito().orElse(null) : null;
    }
}
