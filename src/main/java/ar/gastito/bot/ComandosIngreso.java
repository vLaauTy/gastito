package ar.gastito.bot;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.interpretacion.ParserIngreso;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;

/**
 * /sueldo, /aguinaldo e /ingreso (extra): arman la tarjeta de un ingreso pendiente, que se guarda con un botón
 * como un gasto. Ejemplo: "/ingreso 25000 arreglo de PC a mercado pago".
 */
@Component
public class ComandosIngreso {

    private static final Map<String, ClaseIngreso> COMANDOS = Map.of(
            "/sueldo", ClaseIngreso.SUELDO,
            "/aguinaldo", ClaseIngreso.AGUINALDO,
            "/ingreso", ClaseIngreso.EXTRA);

    private final ParserIngreso parser;
    private final CatalogoService catalogos;
    private final IngresoService ingresos;
    private final ResolvedorFechas fechas;
    private final Tarjetas tarjetas;
    private final Clock clock;

    public ComandosIngreso(ParserIngreso parser, CatalogoService catalogos, IngresoService ingresos,
            ResolvedorFechas fechas, Tarjetas tarjetas, Clock clock) {
        this.parser = parser;
        this.catalogos = catalogos;
        this.ingresos = ingresos;
        this.fechas = fechas;
        this.tarjetas = tarjetas;
        this.clock = clock;
    }

    /** La clase de ingreso de un comando ("/sueldo" → SUELDO), o null si no es un comando de ingreso. */
    static ClaseIngreso clase(String comando) {
        return COMANDOS.get(comando);
    }

    /**
     * El atajo "/sueldo 380.000 galicia", sin IA. Sin nada después del comando, pregunta cuánto. Vacío si no se
     * entiende: entonces lo interpreta la IA, como cualquier mensaje.
     *
     * @param texto el mensaje entero, con el comando
     */
    public Optional<Accion> responder(ClaseIngreso clase, String texto) {
        String[] partes = texto.strip().split("\s+", 2);
        String argumentos = partes.length > 1 ? partes[1] : "";
        if (argumentos.isBlank()) {
            return Optional.of(Accion.Enviar.texto(pregunta(clase)));
        }
        Optional<ParserIngreso.Resultado> leido = parser.parsear(argumentos, catalogos.cargar());
        if (leido.isEmpty()) {
            return Optional.empty();
        }
        ParserIngreso.Resultado r = leido.get();
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        Movimiento m = ingresos.crearPendiente(clase, r.monto(), r.descripcion(), r.cuenta(), r.efectivo(),
                fechas.resolver(r.fecha(), "", ahora), texto.strip());
        return Optional.of(Accion.Enviar.tarjeta(tarjetas.pendiente(m), tarjetas.principal(m), m.getId()));
    }

    /** "Cobré el sueldo, 980 mil en Galicia" (lo entendió la IA): el ingreso pendiente, quizás sin monto. */
    public Movimiento crear(ValidadorInterpretacion.Ingreso i, Origen origen, String texto) {
        return ingresos.crearPendiente(i.clase(), i.monto(), i.descripcion(), i.cuenta(), i.efectivo(),
                i.fechaHora(), origen, texto);
    }

    /** Cómo empieza la frase equivalente a un comando, para que la IA lo interprete ("cobré el sueldo: …"). */
    static String comoFrase(ClaseIngreso clase) {
        return switch (clase) {
            case SUELDO -> "cobré el sueldo: ";
            case AGUINALDO -> "cobré el aguinaldo: ";
            case EXTRA -> "ingreso extra: ";
        };
    }

    /** El botón "Cambiar el mes del sueldo": pasa el período entre el mes anterior al cobro y el mes del cobro. */
    public Movimiento alternarPeriodo(long id) {
        return ingresos.alternarPeriodo(id);
    }

    private static String pregunta(ClaseIngreso clase) {
        return switch (clase) {
            case SUELDO -> "💰 ¿Cuánto cobraste de sueldo? Contámelo como quieras; si me decís la cuenta, mejor.";
            case AGUINALDO -> "💰 ¿Cuánto cobraste de aguinaldo? Contámelo como quieras; si me decís la cuenta, mejor.";
            case EXTRA -> "💰 ¿Qué cobraste y cuánto? Por ejemplo: <i>me pagaron 25 mil por arreglar una PC</i>.";
        };
    }
}
