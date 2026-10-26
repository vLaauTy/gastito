package ar.gastito.juntada;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * Juntadas: pagué por un grupo. En mis gastos cuenta solo mi parte; el resto es plata a cobrar, que se va cobrando
 * con devoluciones (que no son ingresos) o se da por perdida (y pasa a ser gasto mío).
 */
@Service
public class JuntadaService {

    private final JuntadaRepository juntadas;
    private final ParticipanteRepository participantes;
    private final MovimientoRepository movimientos;
    private final MovimientoService movimientoService;
    private final Clock clock;

    public JuntadaService(JuntadaRepository juntadas, ParticipanteRepository participantes,
            MovimientoRepository movimientos, MovimientoService movimientoService, Clock clock) {
        this.juntadas = juntadas;
        this.participantes = participantes;
        this.movimientos = movimientos;
        this.movimientoService = movimientoService;
        this.clock = clock;
    }

    // --- Crear y cambiar ---

    /**
     * Un gasto pendiente que es una juntada: su monto es el total pagado, que se reparte en partes iguales. El gasto
     * queda con mi parte.
     *
     * @param nombres de los demás, si se dijeron (pueden ser menos que personas - 1)
     */
    @Transactional
    public VistaJuntada crear(Long movimientoId, int personas, List<String> nombres) {
        Movimiento m = movimiento(movimientoId);
        if (m.getMonto() == null) {
            throw new JuntadaInvalidaException("Falta el total que pagaste");
        }
        Juntada j = juntadas.save(new Juntada(m, m.getMonto(), personas, nombres, LocalDateTime.now(clock)));
        return vista(j);
    }

    /** La juntada de un movimiento, o vacío si no es una juntada. */
    @Transactional(readOnly = true)
    public Optional<VistaJuntada> vista(Long movimientoId) {
        return juntadas.findByMovimientoId(movimientoId).map(this::vista);
    }

    /** Se guardó el gasto: queda anotado desde dónde salió el total. */
    @Transactional
    public void alGuardar(Long movimientoId) {
        juntadas.findByMovimientoId(movimientoId).ifPresent(Juntada::anotarBilletera);
    }

    @Transactional
    public VistaJuntada cambiarTotal(Long movimientoId, BigDecimal total) {
        Juntada j = juntada(movimientoId);
        j.cambiarTotal(total);
        return vista(j);
    }

    @Transactional
    public VistaJuntada renombrar(Long participanteId, String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new JuntadaInvalidaException("El nombre no puede quedar vacío");
        }
        Participante p = participante(participanteId);
        p.renombrar(nombre);
        return vista(p.getJuntada());
    }

    /** "Martín no comió carne, puso 5000": las demás partes libres se acomodan para que el total siga cerrando. */
    @Transactional
    public VistaJuntada cambiarParte(Long participanteId, BigDecimal monto) {
        Participante p = participante(participanteId);
        if (p.getEstado() != EstadoParticipante.PENDIENTE) {
            throw new JuntadaInvalidaException(p.getNombre() + " ya no tiene nada pendiente");
        }
        p.getJuntada().cambiarParte(p, monto);
        return vista(p.getJuntada());
    }

    // --- Cobrar ---

    /** Alguien me debe en una juntada guardada: a quién se le puede imputar una devolución. */
    public record Deudor(Long participanteId, String nombre, BigDecimal monto, VistaJuntada juntada) {
    }

    /**
     * A quién puede corresponder una devolución, de lo más probable a lo menos: primero por el nombre (si se dijo),
     * después por la juntada ("del asado") y por el monto. Cada filtro se aplica solo si deja a alguien.
     */
    @Transactional(readOnly = true)
    public List<Deudor> deudores(String persona, String de, BigDecimal monto) {
        List<Deudor> todos = new ArrayList<>();
        for (Juntada j : juntadas.conDeudasPendientes()) {
            VistaJuntada v = vista(j);
            v.pendientes().forEach(p -> todos.add(new Deudor(p.id(), p.nombre(), p.monto(), v)));
        }
        List<Deudor> candidatos = todos;
        if (persona != null && !persona.isBlank()) {
            String buscado = normalizar(persona);
            candidatos = filtrar(candidatos, d -> coinciden(normalizar(d.nombre()), buscado));
        }
        if (de != null && !de.isBlank()) {
            String buscado = normalizar(de);
            candidatos = filtrar(candidatos, d -> d.juntada().descripcion() != null
                    && coinciden(normalizar(d.juntada().descripcion()), buscado));
        }
        if (monto != null) {
            candidatos = filtrar(candidatos, d -> d.monto().subtract(monto).abs().compareTo(BigDecimal.ONE) <= 0);
        }
        return List.copyOf(candidatos);
    }

    private static List<Deudor> filtrar(List<Deudor> deudores, Predicate<Deudor> condicion) {
        List<Deudor> filtrados = deudores.stream().filter(condicion).toList();
        return filtrados.isEmpty() ? deudores : filtrados;
    }

    /** Alguna palabra en común ("Pablo" con "pablo p", "asado" con "Asado del sábado"). */
    private static boolean coinciden(String a, String b) {
        List<String> palabras = Arrays.asList(b.split(" "));
        return Arrays.stream(a.split(" ")).anyMatch(p -> p.length() > 1 && palabras.contains(p));
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    /** "Pablo me pasó 8000": una devolución pendiente, hasta que se elija de qué juntada es. */
    @Transactional
    public Movimiento devolucionPendiente(BigDecimal monto, Cuenta cuenta, MedioPago medio, LocalDateTime fechaHora,
            Origen origen, String textoOriginal) {
        return movimientos.save(Movimiento.devolucion(monto, "Devolución", cuenta, medio, fechaHora, origen,
                textoOriginal, LocalDateTime.now(clock)));
    }

    /** Lo que quedó después de cobrar: la devolución guardada y cómo queda la juntada. */
    public record Cobro(Movimiento devolucion, VistaJuntada juntada, BigDecimal debia) {
    }

    /**
     * La devolución es de esta persona: se guarda (como DEVOLUCION, que no es ingreso) y la persona queda pagada. Si
     * no se dijo en qué cuenta entró, se toma la de la juntada.
     */
    @Transactional
    public Cobro cobrar(Long devolucionId, Long participanteId) {
        Movimiento devolucion = movimiento(devolucionId);
        Participante p = participante(participanteId);
        Juntada j = p.getJuntada();
        BigDecimal debia = p.getMonto();
        devolucion.asignarDevolucion(p.getNombre() + ", de " + descripcion(j), j.getCuenta());
        devolucion.confirmar();
        p.pagado(devolucion.getId(), LocalDateTime.now(clock));
        return new Cobro(devolucion, vista(j), debia);
    }

    /**
     * No me la va a pagar: su parte pasa a ser gasto mío, con la categoría, la billetera y la fecha de la juntada.
     */
    @Transactional
    public VistaJuntada darPorPerdido(Long participanteId) {
        Participante p = participante(participanteId);
        Juntada j = p.getJuntada();
        Movimiento original = j.getMovimiento();
        if (original.getEstado() != EstadoMovimiento.CONFIRMADO) {
            throw new JuntadaInvalidaException("Primero guardá la juntada");
        }
        Movimiento gasto = movimientoService.crearPendiente(MovimientoInterpretado.conCategoria(p.getMonto(),
                descripcion(j) + ": la parte de " + p.getNombre() + " (no la cobré)", original.getCategoria(),
                original.getMedioPago(), original.getCuenta(), original.getTarjeta(), original.getFechaHora()),
                Origen.AUTOMATICO, null);
        gasto.prepararJuntada(p.getMonto());
        gasto.confirmar();
        p.perdido(gasto.getId());
        return vista(j);
    }

    /** Las juntadas guardadas en las que alguien todavía me debe. */
    @Transactional(readOnly = true)
    public List<VistaJuntada> conDeudasPendientes() {
        return juntadas.conDeudasPendientes().stream().map(this::vista).toList();
    }

    // --- Vista ---

    private VistaJuntada vista(Juntada j) {
        Movimiento m = j.getMovimiento();
        List<VistaJuntada.Persona> personas = j.getParticipantes().stream()
                .map(p -> new VistaJuntada.Persona(p.getId(), p.getNombre(), p.getMonto(), p.getEstado(),
                        p.isMontoAMano()))
                .toList();
        return new VistaJuntada(m.getId(), j.getId(), m.getDescripcion(),
                m.getCategoria() == null ? null : m.getCategoria().getNombre(), m.getFechaHora(), j.getTotalPagado(),
                m.getMonto(), j.aCobrar(), j.getPersonas(), billetera(j, m),
                m.getEstado() == EstadoMovimiento.CONFIRMADO, personas);
    }

    /** Desde dónde salió el total: lo anotado al guardar o, mientras está pendiente, lo que tiene el gasto. */
    private static String billetera(Juntada j, Movimiento m) {
        Cuenta cuenta = j.getCuenta() != null ? j.getCuenta() : m.getCuenta();
        if (cuenta != null) {
            return cuenta.getNombre();
        }
        if (j.getTarjeta() != null || m.getTarjeta() != null) {
            return (j.getTarjeta() != null ? j.getTarjeta() : m.getTarjeta()).getNombre();
        }
        MedioPago medio = j.getMedioPago() != null ? j.getMedioPago() : m.getMedioPago();
        return medio == null ? null : medio.getNombre();
    }

    private static String descripcion(Juntada j) {
        String d = j.getMovimiento().getDescripcion();
        return d == null || d.isBlank() ? "la juntada" : d;
    }

    private Juntada juntada(Long movimientoId) {
        return juntadas.findByMovimientoId(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("El movimiento " + movimientoId + " no es una juntada"));
    }

    private Participante participante(Long id) {
        return participantes.findById(id).orElseThrow(() -> new NoSuchElementException("No existe la persona " + id));
    }

    private Movimiento movimiento(Long id) {
        return movimientos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
    }
}
