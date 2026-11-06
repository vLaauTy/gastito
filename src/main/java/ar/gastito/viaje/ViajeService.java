package ar.gastito.viaje;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.CategoriaRepository;
import ar.gastito.inversion.ReservaInversion;
import ar.gastito.inversion.ReservaInversionRepository;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.prestamo.PrestamoService;
import ar.gastito.prestamo.VistaPrestamo;

/**
 * Los viajes: una etiqueta para los movimientos, lo que falta pagar, el modo viaje (lo que se carga con la fecha de
 * un viaje se asocia solo) y el panel con lo pagado, lo que falta, lo que me deben y cuánto ahorrar. Las cuentas las
 * hace Java (regla 2).
 */
@Service
public class ViajeService {

    private final ViajeRepository viajes;
    private final PendienteViajeRepository pendientesRepo;
    private final MovimientoRepository movimientos;
    private final MovimientoService movimientoService;
    private final CategoriaRepository categorias;
    private final PrestamoService prestamos;
    private final JuntadaService juntadas;
    private final ReservaInversionRepository reservas;
    private final ViajesProperties properties;
    private final Clock clock;

    public ViajeService(ViajeRepository viajes, PendienteViajeRepository pendientesRepo,
            MovimientoRepository movimientos, MovimientoService movimientoService,
            CategoriaRepository categorias, PrestamoService prestamos, JuntadaService juntadas,
            ReservaInversionRepository reservas, ViajesProperties properties, Clock clock) {
        this.viajes = viajes;
        this.pendientesRepo = pendientesRepo;
        this.movimientos = movimientos;
        this.movimientoService = movimientoService;
        this.categorias = categorias;
        this.prestamos = prestamos;
        this.juntadas = juntadas;
        this.reservas = reservas;
        this.properties = properties;
        this.clock = clock;
    }

    /** Lo que se carga de un viaje: el emoji, las personas, el presupuesto y las notas son opcionales. */
    public record Datos(String nombre, String emoji, LocalDate desde, LocalDate hasta, Integer personas,
            BigDecimal presupuesto, String notas) {
    }

    // --- Viajes ---

    @Transactional
    public PanelViaje crear(Datos d) {
        Viaje v = viajes.save(new Viaje(d.nombre(), d.emoji(), d.desde(), d.hasta(), d.personas(), d.presupuesto(),
                d.notas(), LocalDateTime.now(clock)));
        return panel(v);
    }

    @Transactional
    public PanelViaje editar(Long id, Datos d) {
        Viaje v = viaje(id);
        v.editar(d.nombre(), d.emoji(), d.desde(), d.hasta(), d.personas(), d.presupuesto(), d.notas());
        return panel(v);
    }

    /** Se borra el viaje: los movimientos y préstamos quedan, sin viaje. */
    @Transactional
    public void borrar(Long id) {
        Viaje v = viaje(id);
        movimientos.desasociarViaje(id);
        prestamos.desasociarViaje(id);
        viajes.delete(v);
    }

    @Transactional(readOnly = true)
    public PanelViaje panel(Long id) {
        return panel(viaje(id));
    }

    /** Los viajes cargados, para reconocerlos por el nombre en un mensaje. */
    @Transactional(readOnly = true)
    public List<Viaje> lista() {
        return viajes.findAllByOrderByDesdeDesc();
    }

    /** Todos, del más reciente al más viejo. */
    @Transactional(readOnly = true)
    public List<PanelViaje> todos() {
        return viajes.findAllByOrderByDesdeDesc().stream().map(this::panel).toList();
    }

    /** El viaje en curso o, si no hay, el próximo; si tampoco, el último que terminó. */
    @Transactional(readOnly = true)
    public Optional<PanelViaje> actualOProximo() {
        LocalDate hoy = LocalDate.now(clock);
        List<Viaje> todos = viajes.findAllByOrderByDesdeDesc();
        Optional<Viaje> enCurso = todos.stream().filter(v -> v.incluye(hoy)).findFirst();
        Optional<Viaje> proximo = todos.stream().filter(v -> v.getDesde().isAfter(hoy))
                .min(Comparator.comparing(Viaje::getDesde));
        return enCurso.or(() -> proximo).or(() -> todos.stream().findFirst()).map(this::panel);
    }

    /** Los viajes cuyo nombre aparece en el texto ("para Bariloche me falta…"), el más largo primero. */
    @Transactional(readOnly = true)
    public Optional<Viaje> nombradoEn(String texto) {
        String t = " " + normalizar(texto) + " ";
        return viajes.findAll().stream().filter(v -> t.contains(" " + normalizar(v.getNombre()) + " "))
                .max(Comparator.comparingInt((Viaje v) -> v.getNombre().length())
                        .thenComparing(Viaje::getDesde));
    }

    @Transactional(readOnly = true)
    public Optional<String> nombreConEmoji(Long id) {
        return id == null ? Optional.empty() : viajes.findById(id).map(Viaje::getNombreConEmoji);
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    // --- Modo viaje ---

    @Transactional(readOnly = true)
    public Optional<Long> viajeDe(LocalDate dia) {
        return viajes.queIncluyen(dia).stream().findFirst().map(Viaje::getId);
    }

    /** "No es del viaje": el gasto (y el préstamo que haya salido de él) queda sin viaje. */
    @Transactional
    public Movimiento quitarDelViaje(Long movimientoId) {
        Movimiento m = movimientoService.asociarViaje(movimientoId, null);
        prestamos.seguirViajeDelGasto(movimientoId, null);
        return m;
    }

    // --- Pendientes ---

    @Transactional
    public PanelViaje agregarPendiente(Long viajeId, String descripcion, BigDecimal montoAproximado,
            Long categoriaId) {
        Viaje v = viaje(viajeId);
        v.agregarPendiente(descripcion, montoAproximado, categoriaId, LocalDateTime.now(clock));
        viajes.flush();
        return panel(v);
    }

    /** El pendiente que se acaba de agregar (el último del viaje). */
    @Transactional(readOnly = true)
    public PanelViaje.Pendiente ultimoPendiente(Long viajeId) {
        List<PanelViaje.Pendiente> pendientes = panel(viajeId).pendientes();
        return pendientes.get(pendientes.size() - 1);
    }

    @Transactional
    public PanelViaje editarPendiente(Long pendienteId, String descripcion, BigDecimal montoAproximado,
            Long categoriaId) {
        PendienteViaje p = pendiente(pendienteId);
        p.editar(descripcion, montoAproximado, categoriaId);
        return panel(p.getViaje());
    }

    @Transactional
    public PanelViaje borrarPendiente(Long pendienteId) {
        PendienteViaje p = pendiente(pendienteId);
        Viaje v = p.getViaje();
        v.getPendientes().remove(p);
        return panel(v);
    }

    /**
     * "✅ Pagué el hospedaje" en el bot: el gasto del viaje, pendiente de confirmar, con el monto aproximado (si no
     * tiene, se pide en la tarjeta) y su categoría. Queda pagado cuando se guarda el gasto.
     */
    @Transactional
    public Movimiento pendienteComoGasto(Long pendienteId) {
        PendienteViaje p = pendiente(pendienteId);
        if (p.isPagado()) {
            throw new ViajeInvalidoException(p.getDescripcion() + " ya está pagado");
        }
        Viaje v = p.getViaje();
        MovimientoInterpretado interpretado = interpretado(p, p.getMontoAproximado(), LocalDateTime.now(clock));
        Movimiento m = movimientoService.crearPendiente(interpretado, Origen.TEXTO, null);
        m.asociarViaje(v.getId());
        p.pagarCon(m, LocalDateTime.now(clock));
        return m;
    }

    /**
     * Se tildó en el dashboard: el gasto del viaje, guardado.
     *
     * @param medioPagoId el medio (o −id de una tarjeta), como en los demás formularios; null si no se sabe
     */
    @Transactional
    public PanelViaje pagarPendiente(Long pendienteId, BigDecimal monto, Long categoriaId, Long medioPagoId,
            Long tarjetaId, LocalDate fecha) {
        PendienteViaje p = pendiente(pendienteId);
        if (p.isPagado()) {
            throw new ViajeInvalidoException(p.getDescripcion() + " ya está pagado");
        }
        BigDecimal real = monto != null ? monto : p.getMontoAproximado();
        if (real == null || real.signum() <= 0) {
            throw new ViajeInvalidoException("Falta cuánto pagaste");
        }
        Long categoria = categoriaId != null ? categoriaId : p.getCategoriaId();
        if (categoria != null) {
            p.editar(p.getDescripcion(), p.getMontoAproximado(), categoria);
        }
        LocalDateTime cuando = (fecha == null ? LocalDate.now(clock) : fecha).atTime(LocalDateTime.now(clock)
                .toLocalTime().withSecond(0).withNano(0));
        Movimiento m = movimientoService.crearPendiente(interpretado(p, real, cuando), Origen.DASHBOARD, null);
        m.asociarViaje(p.getViaje().getId());
        if (medioPagoId != null || tarjetaId != null) {
            movimientoService.cambiarMedioPago(m.getId(), medioPagoId, tarjetaId);
        }
        movimientoService.confirmar(m.getId());
        p.pagarCon(m, LocalDateTime.now(clock));
        return panel(p.getViaje());
    }

    private MovimientoInterpretado interpretado(PendienteViaje p, BigDecimal monto, LocalDateTime cuando) {
        String descripcion = p.getDescripcion() + " (" + p.getViaje().getNombre() + ")";
        Optional<Categoria> categoria = p.getCategoriaId() == null ? Optional.empty()
                : categorias.findById(p.getCategoriaId());
        if (categoria.isPresent()) {
            return MovimientoInterpretado.conCategoria(monto, descripcion, categoria.get(), null, null, null, cuando);
        }
        List<Categoria> candidatas = categorias.findByActivaTrueOrderByNombre().stream()
                .filter(c -> c.getNombre().startsWith("Salidas") || c.getNombre().startsWith("Transporte")
                        || c.getNombre().equals("Otros"))
                .toList();
        return new MovimientoInterpretado(monto, descripcion, null,
                candidatas.isEmpty() ? categorias.findByActivaTrueOrderByNombre().subList(0, 1) : candidatas, null,
                null, null, cuando);
    }

    // --- Avisos ---

    /** Los viajes que empiezan hoy (o empezaron y siguen) sin el aviso "Arrancó el modo viaje". */
    @Transactional(readOnly = true)
    public List<PanelViaje> paraAvisarInicio() {
        LocalDate hoy = LocalDate.now(clock);
        return viajes.findAll().stream().filter(v -> v.getAvisoInicioEn() == null && v.incluye(hoy))
                .map(this::panel).toList();
    }

    /** Los viajes cuyo último día es hoy, sin el aviso de cierre. */
    @Transactional(readOnly = true)
    public List<PanelViaje> paraAvisarFin() {
        LocalDate hoy = LocalDate.now(clock);
        return viajes.findAll().stream().filter(v -> v.getAvisoFinEn() == null && v.getHasta().equals(hoy))
                .map(this::panel).toList();
    }

    /** Los viajes que ya terminaron, sin el resumen final (si la PC estuvo apagada, llega al prenderla). */
    @Transactional(readOnly = true)
    public List<PanelViaje> paraResumenFinal() {
        LocalDate hoy = LocalDate.now(clock);
        return viajes.findAll().stream().filter(v -> v.getResumenFinalEn() == null && v.getHasta().isBefore(hoy)
                && v.getHasta().isAfter(hoy.minusDays(30))).map(this::panel).toList();
    }

    @Transactional
    public void avisadoInicio(Long id) {
        viaje(id).avisadoInicio(LocalDateTime.now(clock));
    }

    @Transactional
    public void avisadoFin(Long id) {
        viaje(id).avisadoFin(LocalDateTime.now(clock));
    }

    @Transactional
    public void resumenFinalEnviado(Long id) {
        Viaje v = viaje(id);
        v.resumenFinalEnviado(LocalDateTime.now(clock));
        // Si terminó sin el aviso del último día (la PC apagada), ya no hace falta.
        if (v.getAvisoFinEn() == null) {
            v.avisadoFin(LocalDateTime.now(clock));
        }
    }

    /**
     * Mientras dura el viaje, para el botón "Hoy": lo gastado con fecha dentro del viaje y en cuántos días.
     *
     * @param porDia lo gastado dividido por los días que van (redondeado a peso)
     */
    public record EnCurso(String nombreConEmoji, BigDecimal gastado, long dias, BigDecimal porDia) {
    }

    @Transactional(readOnly = true)
    public Optional<EnCurso> enCurso() {
        LocalDate hoy = LocalDate.now(clock);
        return viajes.queIncluyen(hoy).stream().findFirst().map(v -> {
            BigDecimal gastado = movimientos.gastosDelViaje(v.getId()).stream()
                    .filter(m -> v.incluye(m.getFechaHora().toLocalDate())).map(Movimiento::getMonto)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long dias = ChronoUnit.DAYS.between(v.getDesde(), hoy) + 1;
            return new EnCurso(v.getNombreConEmoji(), gastado, dias,
                    gastado.divide(BigDecimal.valueOf(dias), 0, RoundingMode.HALF_UP).setScale(2));
        });
    }

    // --- Panel ---

    private PanelViaje panel(Viaje v) {
        LocalDate hoy = LocalDate.now(clock);
        List<Movimiento> gastos = movimientos.gastosDelViaje(v.getId());
        BigDecimal pagado = suma(gastos.stream().map(Movimiento::getMonto).toList());

        List<PanelViaje.Pendiente> pendientes = v.getPendientes().stream()
                .map(p -> new PanelViaje.Pendiente(p.getId(), p.getDescripcion(), p.getMontoAproximado(),
                        p.getCategoriaId(), p.isPagado(), p.getMovimiento() == null ? null : p.getMovimiento().getId(),
                        p.isPagado() ? p.getMovimiento().getMonto() : null))
                .toList();
        BigDecimal falta = suma(pendientes.stream().filter(p -> !p.pagado() && p.montoAproximado() != null)
                .map(PanelViaje.Pendiente::montoAproximado).toList());
        int sinMonto = (int) pendientes.stream().filter(p -> !p.pagado() && p.montoAproximado() == null).count();
        BigDecimal totalEstimado = pagado.add(falta);

        List<VistaPrestamo> prestados = prestamos.delViaje(v.getId());
        List<VistaJuntada> compartidas = juntadas.delViaje(v.getId());
        BigDecimal meDeben = suma(prestados.stream().map(VistaPrestamo::restante).toList())
                .add(suma(compartidas.stream().map(VistaJuntada::aCobrar).toList()));
        BigDecimal porLosDemas = suma(prestados.stream().map(VistaPrestamo::monto).toList())
                .add(suma(compartidas.stream().map(j -> j.totalPagado().subtract(j.miParte())).toList()));

        String estado = hoy.isBefore(v.getDesde()) ? PanelViaje.PROXIMO
                : hoy.isAfter(v.getHasta()) ? PanelViaje.TERMINADO : PanelViaje.EN_CURSO;
        AhorroViaje ahorro = estado.equals(PanelViaje.PROXIMO) && falta.signum() > 0 ? ahorro(v, falta, hoy) : null;

        return new PanelViaje(v.getId(), v.getNombre(), v.getEmoji(), v.getDesde(), v.getHasta(), v.dias(),
                v.getPersonas(), v.getPresupuesto(), v.getNotas(), estado, pagado, falta, sinMonto, totalEstimado,
                porcentaje(totalEstimado, v.getPresupuesto()), meDeben, porLosDemas, pendientes,
                porCategoria(gastos, pagado), porDia(v, gastos), prestados, compartidas, ahorro);
    }

    private AhorroViaje ahorro(Viaje v, BigDecimal falta, LocalDate hoy) {
        BigDecimal reservado = suma(reservas.findByLiberadaEnIsNullOrderById().stream()
                .filter(r -> esDelViaje(r, v)).map(ReservaInversion::getMonto).toList());
        List<Movimiento> sueldos = movimientos.ultimosIngresos(ClaseIngreso.SUELDO, PageRequest.of(0, 1));
        int dia = sueldos.isEmpty() ? properties.diaSueldoPorDefecto()
                : sueldos.get(0).getFechaHora().getDayOfMonth();
        boolean yaCobrado = !sueldos.isEmpty()
                && YearMonth.from(sueldos.get(0).getFechaHora()).equals(YearMonth.from(hoy));
        return AhorroViaje.calcular(falta, reservado, hoy, v.getDesde(), dia, yaCobrado);
    }

    /** La reserva es para este viaje: su meta nombra al viaje ("Bariloche", "viaje a Bariloche"). */
    static boolean esDelViaje(ReservaInversion r, Viaje v) {
        String meta = " " + normalizar(r.getMeta()) + " ";
        String nombre = normalizar(v.getNombre());
        return !nombre.isEmpty() && (meta.contains(" " + nombre + " ") || nombre.contains(meta.strip()));
    }

    private static List<PanelViaje.PorCategoria> porCategoria(List<Movimiento> gastos, BigDecimal total) {
        Map<Long, List<Movimiento>> porId = new LinkedHashMap<>();
        for (Movimiento m : gastos) {
            porId.computeIfAbsent(m.getCategoria() == null ? 0L : m.getCategoria().getId(), k -> new ArrayList<>())
                    .add(m);
        }
        List<PanelViaje.PorCategoria> lista = new ArrayList<>();
        porId.forEach((id, ms) -> {
            Categoria c = ms.get(0).getCategoria();
            BigDecimal suma = suma(ms.stream().map(Movimiento::getMonto).toList());
            lista.add(new PanelViaje.PorCategoria(c == null ? null : c.getId(), c == null ? "Sin categoría"
                    : c.getNombre(), c == null ? null : c.getEmoji(), suma, porcentaje(suma, total)));
        });
        lista.sort(Comparator.comparing(PanelViaje.PorCategoria::total).reversed());
        return lista;
    }

    /** Los días del viaje (aunque no tengan gastos) y, antes, los días en que se pagó algo por adelantado. */
    private static List<PanelViaje.PorDia> porDia(Viaje v, List<Movimiento> gastos) {
        Map<LocalDate, BigDecimal> dias = new TreeMap<>();
        for (LocalDate d = v.getDesde(); !d.isAfter(v.getHasta()); d = d.plusDays(1)) {
            dias.put(d, BigDecimal.ZERO.setScale(2));
        }
        for (Movimiento m : gastos) {
            dias.merge(m.getFechaHora().toLocalDate(), m.getMonto(), BigDecimal::add);
        }
        return dias.entrySet().stream().map(e -> new PanelViaje.PorDia(e.getKey(), e.getValue())).toList();
    }

    private static BigDecimal porcentaje(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return null;
        }
        return parte.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal suma(List<BigDecimal> montos) {
        return montos.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private Viaje viaje(Long id) {
        return viajes.findConPendientesById(id).orElseThrow(() -> new NoSuchElementException("No existe el viaje " + id));
    }

    private PendienteViaje pendiente(Long id) {
        return pendientesRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el pendiente " + id));
    }
}
