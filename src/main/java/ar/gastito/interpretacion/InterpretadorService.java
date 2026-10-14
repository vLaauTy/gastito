package ar.gastito.interpretacion;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.Prompts;
import ar.gastito.movimiento.ConPalabrasClave;
import ar.gastito.movimiento.MedioPago;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.Tarjeta;

/**
 * Interpreta un mensaje de texto en dos niveles: primero el parser simple y, si no alcanza, la IA.
 */
@Service
public class InterpretadorService {

    static final String PROMPT = "interpretar-gasto";
    private static final DateTimeFormatter FORMATO_HOY =
            DateTimeFormatter.ofPattern("EEEE d/M/yyyy", Locale.forLanguageTag("es-AR"));

    private final CatalogoService catalogos;
    private final ParserSimple parser;
    private final ValidadorInterpretacion validador;
    private final ClienteIa ia;
    private final Prompts prompts;
    private final Clock clock;

    public InterpretadorService(CatalogoService catalogos, ParserSimple parser, ValidadorInterpretacion validador,
            ClienteIa ia, Prompts prompts, Clock clock) {
        this.catalogos = catalogos;
        this.parser = parser;
        this.validador = validador;
        this.ia = ia;
        this.prompts = prompts;
        this.clock = clock;
    }

    public sealed interface Resultado {
    }

    /** @param usoIaId registro en uso_ia, o null si se resolvió sin IA */
    public record Interpretado(MovimientoInterpretado movimiento, Long usoIaId) implements Resultado {
    }

    public record FaltaMonto(MovimientoInterpretado sinMonto, Long usoIaId) implements Resultado {
    }

    public record NoEsGasto() implements Resultado {
    }

    /** @throws ar.gastito.ia.IaNoDisponibleException si hacía falta la IA y no se pudo usar */
    public Resultado interpretar(String texto) {
        Catalogo catalogo = catalogos.cargar();
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        Optional<ParserSimple.Resultado> simple = parser.parsear(texto, catalogo);
        if (simple.isPresent()) {
            ParserSimple.Resultado r = simple.get();
            return new Interpretado(MovimientoInterpretado.conCategoria(r.monto(), r.descripcion(), r.categoria(),
                    r.medioPago(), r.medioPago() != null && r.medioPago().esTarjetaDeCredito() ? null : r.cuenta(),
                    tarjeta(r.medioPago(), catalogo), ahora), null);
        }

        String instrucciones = prompts.cargar(PROMPT, Map.of(
                "categorias", lista(catalogo.categorias()),
                "mediosPago", lista(catalogo.mediosPago()),
                "cuentas", lista(catalogo.cuentas()),
                "hoy", FORMATO_HOY.format(ahora)));
        ClienteIa.Respuesta<InterpretacionIa> respuesta =
                ia.extraer(instrucciones, texto, InterpretacionIa.class, PropositoIa.TEXTO);

        return switch (validador.validar(respuesta.valor(), catalogo, ahora)) {
            case ValidadorInterpretacion.Valida v -> new Interpretado(v.movimiento(), respuesta.usoIaId());
            case ValidadorInterpretacion.FaltaMonto f -> new FaltaMonto(f.sinMonto(), respuesta.usoIaId());
            case ValidadorInterpretacion.NoEsGasto n -> new NoEsGasto();
        };
    }

    private static Tarjeta tarjeta(MedioPago medio, Catalogo catalogo) {
        return medio != null && medio.esTarjetaDeCredito() ? catalogo.tarjetaCredito().orElse(null) : null;
    }

    private static String lista(java.util.List<? extends ConPalabrasClave> opciones) {
        return opciones.stream().map(o -> "- " + o.getNombre()).collect(Collectors.joining("\n"));
    }
}
