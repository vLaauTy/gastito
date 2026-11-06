package ar.gastito.prestamo;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.movimiento.AntesDeBorrar;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoEspecialException;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;

/**
 * Préstamos a personas, devueltos en cuotas. No son gasto mío: la plata prestada queda a cobrar, como la parte de los
 * demás en una juntada, y se cobra con devoluciones (que no son ingresos). Las cuentas las hace {@link PlanPrestamo}.
 */
@Service
public class PrestamoService implements AntesDeBorrar {

    private final PrestamoRepository prestamos;
    private final MovimientoRepository movimientos;
    private final PrestamosProperties properties;
    private final Clock clock;

    public PrestamoService(PrestamoRepository prestamos, MovimientoRepository movimientos,
            PrestamosProperties properties, Clock clock) {
        this.prestamos = prestamos;
        this.movimientos = movimientos;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Lo que se dijo del préstamo; lo que falta lo calcula Java.
     *
     * @param monto     cuánto le presté (null si se dijeron las cuotas y su valor)
     * @param primerMes el mes de la primera cuota, o null (el mes siguiente al préstamo)
     * @param dia       el día aproximado en que paga, o null (el día del préstamo)
     */
    public record Datos(String persona, BigDecimal monto, Integer cuotas, BigDecimal valorCuota, YearMonth primerMes,
            Integer dia) {
    }

    // --- Crear ---

    /**
     * "Pagué 293.000 de vuelos; 30.000 son de Martín": el gasto pendiente tiene el total pagado; queda con mi parte
     * (la diferencia) y el préstamo sale de ese mismo pago. Vale mientras el gasto esté guardado.
     *
     * @throws PrestamoInvalidoException si el préstamo no es menor que el total
     */
    @Transactional
    public VistaPrestamo crearConGasto(Long movimientoId, Datos datos) {
        Movimiento m = movimiento(movimientoId);
        if (m.getMonto() == null) {
            throw new PrestamoInvalidoException("Falta el total que pagaste");
        }
        PlanPrestamo.Plan plan = PlanPrestamo.de(datos.monto(), datos.cuotas(), datos.valorCuota());
        if (plan.monto().compareTo(m.getMonto()) >= 0) {
            throw new PrestamoInvalidoException("Lo que le prestaste no puede ser todo lo que pagaste");
        }
        m.cambiarMonto(m.getMonto().subtract(plan.monto()));
        LocalDate fecha = m.getFechaHora().toLocalDate();
        Prestamo p = prestamos.save(new Prestamo(datos.persona(), m.getDescripcion(), plan, primerMes(datos, fecha),
                dia(datos, fecha), fecha, m.getCuenta(), m.getMedioPago(), m, m.getViajeId(), EstadoPrestamo.ACTIVO,
                m.getTextoOriginal(), LocalDateTime.now(clock)));
        return vista(p);
    }

    /** "Le presté 30.000 a Martín, me lo devuelve en 6 cuotas": queda pendiente hasta que se confirme. */
    @Transactional
    public VistaPrestamo crearSolo(Datos datos, String descripcion, Cuenta cuenta, MedioPago medio, LocalDate fecha,
            Long viajeId, String textoOriginal) {
        PlanPrestamo.Plan plan = PlanPrestamo.de(datos.monto(), datos.cuotas(), datos.valorCuota());
        Prestamo p = prestamos.save(new Prestamo(datos.persona(), descripcion, plan, primerMes(datos, fecha),
                dia(datos, fecha), fecha, cuenta, medio, null, viajeId, EstadoPrestamo.PENDIENTE, textoOriginal,
                LocalDateTime.now(clock)));
        return vista(p);
    }

    private static YearMonth primerMes(Datos datos, LocalDate fecha) {
        return datos.primerMes() != null ? datos.primerMes() : YearMonth.from(fecha).plusMonths(1);
    }

    private static int dia(Datos datos, LocalDate fecha) {
        return datos.dia() != null && datos.dia() >= 1 && datos.dia() <= 31 ? datos.dia() : fecha.getDayOfMonth();
    }

    @Transactional
    public VistaPrestamo confirmar(Long id) {
        Prestamo p = prestamo(id);
        p.confirmar();
        return vista(p);
    }

    /** Un préstamo cargado solo que no se confirmó: se borra. */
    @Transactional
    public void cancelar(Long id) {
        Prestamo p = prestamo(id);
        if (p.getEstado() != EstadoPrestamo.PENDIENTE) {
            throw new PrestamoInvalidoException("Ese préstamo ya estaba guardado");
        }
        prestamos.delete(p);
    }

    /**
     * Se cambió el monto en la tarjeta del gasto: es el total pagado. Mi parte es el total menos el préstamo.
     *
     * @return el gasto con mi parte
     */
    @Transactional
    public Movimiento cambiarTotal(Long movimientoId, BigDecimal total) {
        Prestamo p = prestamos.findByMovimientoId(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("El gasto " + movimientoId + " no tiene un préstamo"));
        if (total.compareTo(p.getMonto()) <= 0) {
            throw new PrestamoInvalidoException("El total tiene que ser mayor que lo que le prestaste a "
                    + p.getPersona());
        }
        p.getMovimiento().cambiarMonto(total.subtract(p.getMonto()));
        return p.getMovimiento();
    }

    // --- Consultar ---

    @Transactional(readOnly = true)
    public Optional<VistaPrestamo> deMovimiento(Long movimientoId) {
        return prestamos.findByMovimientoId(movimientoId).map(this::vista);
    }

    @Transactional(readOnly = true)
    public VistaPrestamo vista(Long id) {
        return vista(prestamo(id));
    }

    /** Los préstamos en los que todavía me deben algo, del más nuevo al más viejo. */
    @Transactional(readOnly = true)
    public List<VistaPrestamo> conDeuda() {
        return prestamos.conDeuda().stream().map(this::vista).toList();
    }

    /** Todos los que valen (con deuda y terminados). */
    @Transactional(readOnly = true)
    public List<VistaPrestamo> todos() {
        return prestamos.vigentes().stream().map(this::vista).toList();
    }

    @Transactional(readOnly = true)
    public List<VistaPrestamo> delViaje(Long viajeId) {
        return prestamos.delViaje(viajeId).stream().map(this::vista).toList();
    }

    /**
     * A qué préstamo puede corresponder una devolución: primero por el nombre (si se dijo), después por el monto (la
     * próxima cuota o todo lo que debe). Cada filtro se aplica solo si deja a alguno.
     */
    @Transactional(readOnly = true)
    public List<VistaPrestamo> deudores(String persona, BigDecimal monto) {
        List<VistaPrestamo> candidatos = conDeuda();
        if (persona != null && !persona.isBlank()) {
            String buscado = normalizar(persona);
            List<VistaPrestamo> porNombre = candidatos.stream()
                    .filter(v -> coinciden(normalizar(v.persona()), buscado)).toList();
            if (porNombre.isEmpty()) {
                return List.of();
            }
            candidatos = porNombre;
        }
        if (monto != null && candidatos.size() > 1) {
            List<VistaPrestamo> porMonto = candidatos.stream().filter(v -> v.proxima() != null
                    && (iguales(v.proxima().monto(), monto) || iguales(v.restante(), monto))).toList();
            candidatos = porMonto.isEmpty() ? candidatos : porMonto;
        }
        return List.copyOf(candidatos);
    }

    private static boolean iguales(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(BigDecimal.ONE) <= 0;
    }

    /** Alguna palabra en común ("Martín" con "martin g"). */
    private static boolean coinciden(String a, String b) {
        List<String> palabras = Arrays.asList(b.split(" "));
        return Arrays.stream(a.split(" ")).anyMatch(p -> p.length() > 1 && palabras.contains(p));
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    // --- Cobrar ---

    /**
     * Lo que quedó después de cobrar.
     *
     * @param cuotas    cuántas cuotas cubrió este pago
     * @param excedente lo que pagó de más sobre todo lo que debía
     */
    public record Cobro(Movimiento devolucion, VistaPrestamo prestamo, int cuotas, BigDecimal excedente,
            BigDecimal debia) {

        /** Pagó distinto de lo que valían las cuotas que cubrió: las que siguen se acomodaron. */
        public boolean acomodado() {
            return !prestamo.terminado() && devolucion.getMonto().compareTo(debia) != 0;
        }
    }

    /**
     * La devolución (pendiente) es de este préstamo: se guarda como DEVOLUCION (no es ingreso) y se marcan las cuotas
     * que cubre. Si no se dijo en qué cuenta entró, se toma la del préstamo.
     */
    @Transactional
    public Cobro cobrar(Long devolucionId, Long prestamoId) {
        Movimiento devolucion = movimiento(devolucionId);
        Prestamo p = prestamo(prestamoId);
        if (!p.isVigente()) {
            throw new PrestamoInvalidoException("Ese préstamo ya no está");
        }
        // Lo que valían las cuotas antes de este pago (cobrar las acomoda).
        List<CuotaPrestamo> porCobrar = p.porCobrar();
        List<BigDecimal> antes = porCobrar.stream().map(CuotaPrestamo::getMonto).toList();
        int primera = porCobrar.isEmpty() ? 0 : porCobrar.get(0).getNumero();
        PlanPrestamo.Aplicacion a = p.cobrar(devolucion.getMonto(), devolucion.getId(), LocalDateTime.now(clock));
        BigDecimal debia = antes.subList(0, a.cubiertas()).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        String cuales = a.cubiertas() == 1 ? "cuota " + primera
                : "cuotas " + primera + " a " + (primera + a.cubiertas() - 1);
        devolucion.asignarDevolucion(p.getPersona() + ", " + cuales + " de " + p.getCantidadCuotas()
                + (p.getDescripcion() == null ? "" : " (" + p.getDescripcion() + ")"), cuenta(p));
        devolucion.confirmar();
        return new Cobro(devolucion, vista(p), a.cubiertas(), a.excedente(), debia);
    }

    /**
     * "✅ Ya me la pasó" desde el aviso de una cuota atrasada: una devolución por la próxima cuota, a la cuenta del
     * préstamo, guardada.
     */
    @Transactional
    public Cobro cobrarProxima(Long prestamoId, Origen origen) {
        Prestamo p = prestamo(prestamoId);
        CuotaPrestamo proxima = p.proxima()
                .orElseThrow(() -> new PrestamoInvalidoException(p.getPersona() + " ya te pagó todo"));
        Movimiento devolucion = movimientos.save(Movimiento.devolucion(proxima.getMonto(), "Devolución",
                cuenta(p), p.getCuenta() == null ? p.getMedioPago() : null, LocalDateTime.now(clock), origen, null,
                LocalDateTime.now(clock)));
        return cobrar(devolucion.getId(), prestamoId);
    }

    private static Cuenta cuenta(Prestamo p) {
        if (p.getCuenta() != null) {
            return p.getCuenta();
        }
        return p.getMovimiento() == null ? null : p.getMovimiento().getCuenta();
    }

    // --- Atrasadas ---

    /** Una cuota atrasada para avisar una vez. */
    public record Atrasada(VistaPrestamo prestamo, VistaPrestamo.Cuota cuota) {
    }

    /**
     * Las cuotas que pasaron unos días de su fecha sin cobrarse quedan atrasadas; devuelve las que todavía no se
     * avisaron (una por préstamo, la más vieja).
     */
    @Transactional
    public List<Atrasada> paraAvisar() {
        LocalDate limite = LocalDate.now(clock).minusDays(properties.diasDeGracia());
        List<Atrasada> avisar = new ArrayList<>();
        for (Prestamo p : prestamos.conDeuda()) {
            CuotaPrestamo vieja = null;
            for (CuotaPrestamo c : p.porCobrar()) {
                if (c.getVence().isBefore(limite)) {
                    c.atrasar();
                    if (vieja == null && c.getAvisoEnviadoEn() == null) {
                        vieja = c;
                    }
                }
            }
            if (vieja != null) {
                avisar.add(new Atrasada(vista(p), cuota(vieja)));
            }
        }
        return avisar;
    }

    /** Telegram recibió el aviso de esta cuota: no se vuelve a mandar. */
    @Transactional
    public void avisada(Long prestamoId, Long cuotaId) {
        prestamo(prestamoId).getCuotas().stream().filter(c -> c.getId().equals(cuotaId)).findFirst()
                .ifPresent(c -> c.avisada(LocalDateTime.now(clock)));
    }

    // --- Viajes ---

    /** El préstamo pasa a ser de un viaje (o de ninguno). */
    @Transactional
    public void asociarViaje(Long prestamoId, Long viajeId) {
        prestamo(prestamoId).asociarViaje(viajeId);
    }

    /** El gasto del que salió el préstamo cambió de viaje: el préstamo lo sigue. */
    @Transactional
    public void seguirViajeDelGasto(Long movimientoId, Long viajeId) {
        prestamos.findByMovimientoId(movimientoId).ifPresent(p -> p.asociarViaje(viajeId));
    }

    // --- Borrar ---

    /**
     * Se va a borrar un gasto: si de ese pago salió un préstamo, se borra con él, salvo que ya me haya pagado alguna
     * cuota (ahí no se borra el gasto).
     */
    @Override
    public void antesDeBorrar(Movimiento m) {
        prestamos.findByMovimientoId(m.getId()).ifPresent(p -> {
            if (p.getCuotasCobradas() > 0) {
                throw new MovimientoEspecialException("De este pago salió el préstamo a " + p.getPersona()
                        + ", que ya te pagó cuotas: no se borra");
            }
            prestamos.delete(p);
            prestamos.flush();
        });
    }

    // --- Vista ---

    private VistaPrestamo vista(Prestamo p) {
        List<VistaPrestamo.Cuota> cuotas = p.getCuotas().stream().map(PrestamoService::cuota).toList();
        return new VistaPrestamo(p.getId(), p.getPersona(), p.getDescripcion(), p.getMonto(), p.getCantidadCuotas(),
                p.getValorCuota(), p.getPrimerMes(), p.getDia(), p.getFecha(), billetera(p),
                p.getMovimiento() == null ? null : p.getMovimiento().getId(), p.getViajeId(), p.getEstado(),
                p.getCobrado(), p.getRestante(), p.getCuotasCobradas(), cuotas);
    }

    private static VistaPrestamo.Cuota cuota(CuotaPrestamo c) {
        return new VistaPrestamo.Cuota(c.getId(), c.getNumero(), c.getVence(), c.getMonto(), c.getEstado());
    }

    private static String billetera(Prestamo p) {
        Cuenta cuenta = cuenta(p);
        if (cuenta != null) {
            return cuenta.getNombre();
        }
        MedioPago medio = p.getMedioPago() != null ? p.getMedioPago()
                : p.getMovimiento() == null ? null : p.getMovimiento().getMedioPago();
        return medio == null ? null : medio.getNombre();
    }

    private Prestamo prestamo(Long id) {
        return prestamos.findConCuotasById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el préstamo " + id));
    }

    private Movimiento movimiento(Long id) {
        return movimientos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + id));
    }
}
