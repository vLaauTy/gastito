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
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.IngresoService;
import ar.gastito.movimiento.Movimiento;

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

    /** @param texto el mensaje entero, con el comando ("/sueldo 380.000 galicia") */
    public Accion responder(ClaseIngreso clase, String texto) {
        String[] partes = texto.strip().split("\\s+", 2);
        String argumentos = partes.length > 1 ? partes[1] : "";
        Optional<ParserIngreso.Resultado> leido = parser.parsear(argumentos, catalogos.cargar());
        if (leido.isEmpty()) {
            return Accion.Enviar.texto(ejemplo(clase, argumentos.isBlank()));
        }
        ParserIngreso.Resultado r = leido.get();
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        Movimiento m = ingresos.crearPendiente(clase, r.monto(), r.descripcion(), r.cuenta(), r.efectivo(),
                fechas.resolver(r.fecha(), "", ahora), texto.strip());
        return Accion.Enviar.tarjeta(tarjetas.pendiente(m), tarjetas.principal(m), m.getId());
    }


    /** El botón "Cambiar el mes del sueldo": pasa el período entre el mes anterior al cobro y el mes del cobro. */
    public Movimiento alternarPeriodo(long id) {
        return ingresos.alternarPeriodo(id);
    }

    private static String ejemplo(ClaseIngreso clase, boolean sinNada) {
        String comando = COMANDOS.entrySet().stream().filter(e -> e.getValue() == clase).findFirst()
                .orElseThrow().getKey();
        String ejemplo = switch (clase) {
            case SUELDO -> comando + " 380.000 galicia";
            case AGUINALDO -> comando + " 190 mil galicia";
            case EXTRA -> comando + " 25000 arreglo de PC a mercado pago";
        };
        return (sinNada ? "Decime el monto." : "No entendí el monto 🤔") + "\nPor ejemplo: " + ejemplo;
    }
}
