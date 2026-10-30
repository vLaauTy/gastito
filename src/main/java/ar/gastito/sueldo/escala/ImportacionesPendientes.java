package ar.gastito.sueldo.escala;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Las hojas leídas que esperan que el usuario toque Guardar (en el bot o en el dashboard). Viven en memoria: si la
 * aplicación se reinicia, se vuelve a mandar la hoja. Duran un día.
 */
@Component
public class ImportacionesPendientes {

    static final Duration DURACION = Duration.ofDays(1);
    static final int MAXIMO = 20;

    /**
     * @param propuesta null mientras falta saber de qué mes es la planilla
     * @param elegidos  los meses que se van a guardar (al principio, todos)
     */
    public record Pendiente(long id, HojaEscala hoja, ImportacionEscala.Propuesta propuesta, Set<YearMonth> elegidos,
            Instant creada) {
    }

    private final Map<Long, Pendiente> pendientes = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Clock clock;

    public ImportacionesPendientes(Clock clock) {
        this.clock = clock;
    }

    public Pendiente agregar(HojaEscala hoja, ImportacionEscala.Propuesta propuesta) {
        limpiar();
        long id = ids.incrementAndGet();
        Pendiente p = new Pendiente(id, hoja, propuesta, todos(propuesta), clock.instant());
        pendientes.put(id, p);
        return p;
    }

    /** Ya se sabe el mes: la misma importación con su propuesta. */
    public Pendiente conPropuesta(Pendiente p, HojaEscala hoja, ImportacionEscala.Propuesta propuesta) {
        Pendiente nueva = new Pendiente(p.id(), hoja, propuesta, todos(propuesta), p.creada());
        pendientes.put(p.id(), nueva);
        return nueva;
    }

    /** Marca o desmarca un mes para guardar. */
    public Pendiente alternar(Pendiente p, YearMonth mes) {
        Set<YearMonth> elegidos = new LinkedHashSet<>(p.elegidos());
        if (!elegidos.remove(mes)) {
            elegidos.add(mes);
        }
        Pendiente nueva = new Pendiente(p.id(), p.hoja(), p.propuesta(), Set.copyOf(elegidos), p.creada());
        pendientes.put(p.id(), nueva);
        return nueva;
    }

    public Optional<Pendiente> buscar(long id) {
        limpiar();
        return Optional.ofNullable(pendientes.get(id));
    }

    public void quitar(long id) {
        pendientes.remove(id);
    }

    private static Set<YearMonth> todos(ImportacionEscala.Propuesta propuesta) {
        return propuesta == null ? Set.of() : Set.copyOf(propuesta.listaDeMeses());
    }

    private void limpiar() {
        Instant limite = clock.instant().minus(DURACION);
        pendientes.values().removeIf(p -> p.creada().isBefore(limite));
        while (pendientes.size() >= MAXIMO) {
            pendientes.values().stream().min(java.util.Comparator.comparing(Pendiente::creada))
                    .ifPresent(p -> pendientes.remove(p.id()));
        }
    }
}
