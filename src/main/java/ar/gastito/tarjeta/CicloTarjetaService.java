package ar.gastito.tarjeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.cuota.CuotaProperties;
import ar.gastito.cuota.PlanCuotas;
import ar.gastito.cuota.PlanCuotasRepository;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;

/**
 * El ciclo de la tarjeta de crédito: a qué resumen va cada compra y cada cuota, cuánto lleva cada uno, las fechas
 * estimadas hasta que se cargan, los recordatorios y el débito. Las compras siguen contando como gasto el día que se
 * hicieron; esto es para saber cuánto se va a debitar y cuándo.
 * <p>
 * Siempre hay un resumen guardado abierto (el próximo): cada consulta crea, estimados, los que faltan desde el último
 * guardado, y vuelve a estimar los que no tienen fechas confirmadas (por si cambió el confirmado del que salen).
 */
@Service
public class CicloTarjetaService {

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    /** Unas fechas cargadas con más distancia que esta entre cierre y vencimiento seguro están mal. */
    static final int MAXIMO_DIAS_HASTA_VENCIMIENTO = 40;

    private final ResumenTarjetaRepository resumenes;
    private final MovimientoRepository movimientos;
    private final PlanCuotasRepository planes;
    private final CatalogoService catalogos;
    private final CuotaProperties cuotas;
    private final TarjetaProperties properties;
    private final Clock clock;

    public CicloTarjetaService(ResumenTarjetaRepository resumenes, MovimientoRepository movimientos,
            PlanCuotasRepository planes, CatalogoService catalogos, CuotaProperties cuotas,
            TarjetaProperties properties, Clock clock) {
        this.resumenes = resumenes;
        this.movimientos = movimientos;
        this.planes = planes;
        this.catalogos = catalogos;
        this.cuotas = cuotas;
        this.properties = properties;
        this.clock = clock;
    }

    /** La tarjeta, sus resúmenes guardados (por período) y el calendario que sale de ellos. */
    private record Ciclo(TarjetaCredito tarjeta, List<ResumenTarjeta> guardados, CalendarioTarjeta calendario) {

        Optional<ResumenTarjeta> guardado(YearMonth periodo) {
            return guardados.stream().filter(r -> r.getPeriodo().equals(periodo)).findFirst();
        }

        ResumenTarjeta abierto() {
            return guardados.stream().filter(r -> r.getEstado() == EstadoResumenTarjeta.ABIERTO).findFirst()
                    .orElseThrow();
        }

        List<ResumenTarjeta> porPagar() {
            return guardados.stream().filter(r -> r.getEstado() == EstadoResumenTarjeta.CERRADO).toList();
        }
    }

    // --- Consultas ---

    /**
     * Todo lo de la tarjeta para mostrar.
     *
     * @param porPagar   los resúmenes cerrados que todavía no se debitaron, del más viejo al más nuevo
     * @param proximo    el resumen abierto: en el que entran las compras de hoy
     * @param siguientes los resúmenes que siguen al próximo con cuotas ya comprometidas
     * @param comprometidoSiguientes lo que suman esas cuotas
     * @param pagados    los últimos debitados, del más nuevo al más viejo
     */
    public record Panel(String tarjeta, String cuentaDebito, List<VistaResumenTarjeta> porPagar,
            VistaResumenTarjeta proximo, List<VistaResumenTarjeta> siguientes, BigDecimal comprometidoSiguientes,
            List<VistaResumenTarjeta> pagados) {
    }

    /** Cuántos resúmenes pagados se muestran. */
    static final int PAGADOS_A_MOSTRAR = 3;

    /** Vacío si no hay tarjeta o todavía no se cargó ningún cierre (no hay de dónde estimar). */
    @Transactional
    public Optional<Panel> panel() {
        return asegurar().map(c -> {
            Map<YearMonth, List<VistaResumenTarjeta.Linea>> cuotasPorResumen = cuotasPorResumen(c);
            Function<ResumenTarjeta, VistaResumenTarjeta> vista = r -> vista(c, r.fechas(), r, cuotasPorResumen);
            ResumenTarjeta abierto = c.abierto();
            List<VistaResumenTarjeta> siguientes = new ArrayList<>();
            for (int i = 1; i <= properties.resumenesSiguientes(); i++) {
                YearMonth periodo = abierto.getPeriodo().plusMonths(i);
                VistaResumenTarjeta v = vista(c, c.calendario().fechas(periodo), c.guardado(periodo).orElse(null),
                        cuotasPorResumen);
                if (!v.detalle().isEmpty()) {
                    siguientes.add(v);
                }
            }
            List<VistaResumenTarjeta> pagados = c.guardados().stream()
                    .filter(r -> r.getEstado() == EstadoResumenTarjeta.PAGADO)
                    .sorted(Comparator.comparing(ResumenTarjeta::getPeriodo).reversed())
                    .limit(PAGADOS_A_MOSTRAR).map(vista).toList();
            return new Panel(c.tarjeta().getNombre(), cuentaDebito(c.tarjeta()),
                    c.porPagar().stream().map(vista).toList(), vista.apply(abierto), siguientes,
                    siguientes.stream().map(VistaResumenTarjeta::cuotas).reduce(BigDecimal.ZERO, BigDecimal::add),
                    pagados);
        });
    }

    /**
     * Las fechas del resumen en el que entra un gasto con la tarjeta (de una cuota, el de esa cuota). Vacío si no es
     * con la tarjeta o el ciclo no está configurado. Solo lee: no crea resúmenes.
     */
    @Transactional(readOnly = true)
    public Optional<CalendarioTarjeta.Fechas> resumenDe(Movimiento m) {
        if (m.getTarjeta() == null || m.getFechaHora() == null) {
            return Optional.empty();
        }
        List<ResumenTarjeta> guardados = resumenes.findByTarjetaIdOrderByPeriodo(m.getTarjeta().getId());
        CalendarioTarjeta calendario = new CalendarioTarjeta(guardados.stream().map(ResumenTarjeta::fechas).toList());
        if (calendario.isVacio()) {
            return Optional.empty();
        }
        YearMonth periodo;
        if (m.getPlanCuotasId() != null && m.getCuotaActual() != null) {
            LocalDateTime compra = planes.findById(m.getPlanCuotasId()).map(PlanCuotas::getFechaCompra)
                    .orElse(m.getFechaHora());
            periodo = primerResumenDeCuotas(calendario, compra).plusMonths(m.getCuotaActual() - 1L);
        } else {
            periodo = calendario.periodoDe(m.getFechaHora().toLocalDate());
        }
        // Lo guardado manda (puede tener las fechas que se cargaron); si no, la estimación.
        return Optional.of(guardados.stream().filter(r -> r.getPeriodo().equals(periodo)).findFirst()
                .filter(ResumenTarjeta::isFechasConfirmadas).map(ResumenTarjeta::fechas)
                .orElseGet(() -> calendario.fechas(periodo)));
    }

    // --- Cargar fechas, total y pago ---

    /**
     * Las fechas que cargó el usuario ("cierra el 26/11 y vence el 11/12"), para el resumen del mes que corresponda.
     * Las compras se reasignan solas: el período sale de las fechas.
     *
     * @param antes las fechas que tenía ese resumen (estimadas o no), o null si es el primero
     */
    public record FechasCargadas(VistaResumenTarjeta resumen, CalendarioTarjeta.Fechas antes) {
    }

    @Transactional
    public FechasCargadas cargarFechas(LocalDate cierre, LocalDate vencimiento) {
        if (!vencimiento.isAfter(cierre)) {
            throw new IllegalArgumentException("El vencimiento tiene que ser después del cierre");
        }
        if (ChronoUnit.DAYS.between(cierre, vencimiento) > MAXIMO_DIAS_HASTA_VENCIMIENTO) {
            throw new IllegalArgumentException("Entre el cierre y el vencimiento no puede haber más de "
                    + MAXIMO_DIAS_HASTA_VENCIMIENTO + " días");
        }
        TarjetaCredito tarjeta = tarjeta().orElseThrow(() -> new IllegalStateException("No hay una tarjeta activa"));
        LocalDate hoy = LocalDate.now(clock);
        List<ResumenTarjeta> guardados = resumenes.findByTarjetaIdOrderByPeriodo(tarjeta.getId());
        CalendarioTarjeta calendario = new CalendarioTarjeta(guardados.stream().map(ResumenTarjeta::fechas).toList());
        YearMonth periodo = calendario.periodoParaCierre(cierre);
        for (ResumenTarjeta vecino : guardados) {
            boolean anterior = vecino.getPeriodo().isBefore(periodo) && !vecino.getCierre().isBefore(cierre);
            boolean posterior = vecino.getPeriodo().isAfter(periodo) && !vecino.getCierre().isAfter(cierre);
            if (vecino.isFechasConfirmadas() && (anterior || posterior)) {
                throw new IllegalArgumentException("Ese cierre se cruza con el del resumen que cierra el "
                        + DIA_MES.format(vecino.getCierre()));
            }
        }
        CalendarioTarjeta.Fechas nuevas = new CalendarioTarjeta.Fechas(periodo, cierre, vencimiento, true);
        Optional<ResumenTarjeta> existente = guardados.stream().filter(r -> r.getPeriodo().equals(periodo))
                .findFirst();
        CalendarioTarjeta.Fechas antes = existente.map(ResumenTarjeta::fechas)
                .orElseGet(() -> calendario.isVacio() ? null : calendario.fechas(periodo));
        ResumenTarjeta resumen = existente.orElseGet(() -> resumenes.save(
                new ResumenTarjeta(tarjeta, nuevas, hoy, LocalDateTime.now(clock))));
        resumen.cambiarFechas(nuevas, hoy);
        resumenes.flush();
        Ciclo c = asegurar().orElseThrow();
        ResumenTarjeta actualizado = c.guardado(periodo).orElseThrow();
        return new FechasCargadas(vista(c, actualizado.fechas(), actualizado, cuotasPorResumen(c)), antes);
    }

    /**
     * El total que dice el resumen del banco, comparado con lo cargado en Gastito.
     *
     * @param diferencia total − cargado: positiva si falta cargar, negativa si se cargó de más; cero dentro de la
     *                   tolerancia
     */
    public record TotalCargado(VistaResumenTarjeta resumen, BigDecimal total, BigDecimal cargado,
            BigDecimal diferencia) {

        @com.fasterxml.jackson.annotation.JsonProperty
        public boolean coincide() {
            return diferencia.signum() == 0;
        }
    }

    /** @param resumenId cuál, o null para el cerrado más viejo sin debitar */
    @Transactional
    public TotalCargado cargarTotal(Long resumenId, BigDecimal total) {
        Ciclo c = asegurar().orElseThrow(() -> new NoSuchElementException("Todavía no cargaste ningún cierre"));
        ResumenTarjeta r = resumenId != null ? buscar(c, resumenId) : porPagar(c);
        r.cargarTotal(total);
        VistaResumenTarjeta v = vista(c, r.fechas(), r, cuotasPorResumen(c));
        return new TotalCargado(v, total, v.cargado(), v.diferencia());
    }

    /** Total del resumen − lo cargado; cero dentro de la tolerancia. */
    private BigDecimal diferencia(BigDecimal total, BigDecimal cargado) {
        BigDecimal diferencia = total.subtract(cargado).setScale(2, RoundingMode.HALF_UP);
        return diferencia.abs().compareTo(properties.toleranciaDiferencia()) <= 0 ? BigDecimal.ZERO.setScale(2)
                : diferencia;
    }

    public record Pago(Movimiento movimiento, VistaResumenTarjeta resumen) {
    }

    /**
     * Se debitó el resumen: un PAGO_TARJETA (no cuenta como gasto) desde la cuenta de débito de la tarjeta, y el
     * resumen queda pagado. Sin monto, el total del resumen si se cargó o, si no, lo cargado en Gastito.
     *
     * @param resumenId cuál, o null para el cerrado más viejo sin debitar
     */
    @Transactional
    public Pago pagar(Long resumenId, BigDecimal monto, Origen origen, String textoOriginal) {
        Ciclo c = asegurar().orElseThrow(() -> new NoSuchElementException("Todavía no cargaste ningún cierre"));
        ResumenTarjeta r = resumenId != null ? buscar(c, resumenId) : porPagar(c);
        if (r.getEstado() != EstadoResumenTarjeta.CERRADO) {
            throw new IllegalStateException(r.getEstado() == EstadoResumenTarjeta.PAGADO
                    ? "Ese resumen ya está pagado" : "Ese resumen todavía no cerró");
        }
        VistaResumenTarjeta antes = vista(c, r.fechas(), r, cuotasPorResumen(c));
        BigDecimal debitado = monto != null ? monto : antes.aDebitar();
        if (debitado == null || debitado.signum() <= 0) {
            throw new IllegalArgumentException("No hay un monto para el débito del resumen");
        }
        LocalDateTime ahora = LocalDateTime.now(clock);
        Movimiento pago = movimientos.save(Movimiento.pagoTarjeta(debitado,
                "Resumen " + c.tarjeta().getNombre() + " (vence " + DIA_MES.format(r.getVencimiento()) + ")",
                c.tarjeta(), c.tarjeta().getCuentaDebito(), ahora.truncatedTo(ChronoUnit.MINUTES), origen,
                textoOriginal, ahora));
        if (r.getTotalReal() == null) {
            r.cargarTotal(debitado);
        }
        r.pagar(pago.getId());
        return new Pago(pago, vista(c, r.fechas(), r, cuotasPorResumen(c)));
    }

    // --- Recordatorios ---

    /**
     * El próximo resumen sigue con las fechas estimadas, ya pasaron los días desde el cierre anterior, hoy no se
     * preguntó y no está pospuesto: hay que preguntar por las fechas.
     */
    @Transactional
    public Optional<VistaResumenTarjeta> preguntaDeFechas() {
        if (antesDeHora()) {
            return Optional.empty();
        }
        LocalDate hoy = LocalDate.now(clock);
        return asegurar().flatMap(c -> {
            ResumenTarjeta proximo = c.abierto();
            LocalDate cierreAnterior = c.calendario().fechas(proximo.getPeriodo().minusMonths(1)).cierre();
            boolean toca = !proximo.isFechasConfirmadas()
                    && !hoy.isBefore(cierreAnterior.plusDays(properties.diasParaPreguntarFechas()))
                    && !hoy.equals(proximo.getPreguntaFechasEn())
                    && (proximo.getFechasPospuestasHasta() == null || !hoy.isBefore(proximo.getFechasPospuestasHasta()));
            return toca ? Optional.of(vista(c, proximo.fechas(), proximo, cuotasPorResumen(c))) : Optional.empty();
        });
    }

    /** Se preguntó (Telegram recibió el mensaje): hoy no se vuelve a preguntar. */
    @Transactional
    public void preguntoFechas(Long resumenId) {
        resumenes.findById(resumenId).ifPresent(r -> r.preguntoFechas(LocalDate.now(clock)));
    }

    /** "Todavía no": se vuelve a preguntar en unos días. */
    @Transactional
    public LocalDate posponerFechas(Long resumenId) {
        LocalDate hasta = LocalDate.now(clock).plusDays(properties.diasAlPosponerFechas());
        resumenes.findById(resumenId).orElseThrow(() -> new NoSuchElementException("No existe el resumen " + resumenId))
                .posponerFechas(hasta);
        return hasta;
    }

    /** Faltan pocos días para el vencimiento de un resumen sin debitar y todavía no se avisó. */
    @Transactional
    public Optional<VistaResumenTarjeta> avisoDeVencimiento() {
        if (antesDeHora()) {
            return Optional.empty();
        }
        LocalDate hoy = LocalDate.now(clock);
        return asegurar().flatMap(c -> c.porPagar().stream()
                .filter(r -> r.getAvisoVencimientoEn() == null && hoy.isBefore(r.getVencimiento())
                        && !hoy.isBefore(r.getVencimiento().minusDays(properties.diasAvisoVencimiento())))
                .findFirst().map(r -> vista(c, r.fechas(), r, cuotasPorResumen(c))));
    }

    @Transactional
    public void avisoVencimientoEnviado(Long resumenId) {
        resumenes.findById(resumenId).ifPresent(r -> r.avisoVencimiento(LocalDate.now(clock)));
    }

    /** Llegó el vencimiento de un resumen sin debitar y hoy no se preguntó: "¿Se debitó?" (una vez por día). */
    @Transactional
    public Optional<VistaResumenTarjeta> preguntaDeDebito() {
        if (antesDeHora()) {
            return Optional.empty();
        }
        LocalDate hoy = LocalDate.now(clock);
        return asegurar().flatMap(c -> c.porPagar().stream()
                .filter(r -> !hoy.isBefore(r.getVencimiento()) && !hoy.equals(r.getPreguntaDebitoEn()))
                .findFirst().map(r -> vista(c, r.fechas(), r, cuotasPorResumen(c))));
    }

    @Transactional
    public void preguntoDebito(Long resumenId) {
        resumenes.findById(resumenId).ifPresent(r -> r.preguntoDebito(LocalDate.now(clock)));
    }

    private boolean antesDeHora() {
        return LocalDateTime.now(clock).getHour() < properties.horaAvisos();
    }

    // --- Calendario ---

    private Optional<TarjetaCredito> tarjeta() {
        return catalogos.cargar().tarjetaCredito();
    }

    /**
     * Pone al día los resúmenes guardados: vuelve a estimar los que no tienen fechas confirmadas, actualiza su
     * estado y crea, estimados, los que faltan hasta el que está abierto hoy. Vacío si no hay tarjeta o ningún
     * cierre cargado.
     */
    private Optional<Ciclo> asegurar() {
        Optional<TarjetaCredito> tarjeta = tarjeta();
        if (tarjeta.isEmpty()) {
            return Optional.empty();
        }
        List<ResumenTarjeta> guardados = new ArrayList<>(
                resumenes.findByTarjetaIdOrderByPeriodo(tarjeta.get().getId()));
        CalendarioTarjeta calendario = new CalendarioTarjeta(guardados.stream().map(ResumenTarjeta::fechas).toList());
        if (calendario.isVacio()) {
            return Optional.empty();
        }
        LocalDate hoy = LocalDate.now(clock);
        for (ResumenTarjeta r : guardados) {
            if (r.isFechasConfirmadas()) {
                r.actualizarEstado(hoy);
            } else {
                r.cambiarFechas(calendario.fechas(r.getPeriodo()), hoy);
            }
        }
        YearMonth ultimo = guardados.get(guardados.size() - 1).getPeriodo();
        while (calendario.fechas(ultimo).cierre().isBefore(hoy)) {
            ultimo = ultimo.plusMonths(1);
            guardados.add(resumenes.save(new ResumenTarjeta(tarjeta.get(), calendario.fechas(ultimo), hoy,
                    LocalDateTime.now(clock))));
        }
        return Optional.of(new Ciclo(tarjeta.get(), guardados, calendario));
    }

    /** El primer resumen de una compra en cuotas: el de la compra, o el siguiente según gastito.cuotas.primera-cuota. */
    private YearMonth primerResumenDeCuotas(CalendarioTarjeta calendario, LocalDateTime compra) {
        YearMonth periodo = calendario.periodoDe(compra.toLocalDate());
        return cuotas.primeraCuota() == CuotaProperties.PrimeraCuota.MES_SIGUIENTE ? periodo.plusMonths(1) : periodo;
    }

    /** Las cuotas con la tarjeta, una por resumen desde el primero de su compra. */
    private Map<YearMonth, List<VistaResumenTarjeta.Linea>> cuotasPorResumen(Ciclo c) {
        List<Movimiento> todas = movimientos.cuotasConTarjeta(c.tarjeta().getId());
        Map<Long, LocalDateTime> compras = planes.findAllById(todas.stream().map(Movimiento::getPlanCuotasId)
                .distinct().toList()).stream()
                .collect(Collectors.toMap(PlanCuotas::getId, PlanCuotas::getFechaCompra));
        Map<Long, YearMonth> primeros = new HashMap<>();
        Map<YearMonth, List<VistaResumenTarjeta.Linea>> porResumen = new HashMap<>();
        for (Movimiento cuota : todas) {
            YearMonth primero = primeros.computeIfAbsent(cuota.getPlanCuotasId(), id -> primerResumenDeCuotas(
                    c.calendario(), compras.getOrDefault(id, cuota.getFechaHora())));
            YearMonth periodo = primero.plusMonths(cuota.getCuotaActual() - 1L);
            porResumen.computeIfAbsent(periodo, p -> new ArrayList<>()).add(linea(cuota, cuota.getMonto()));
        }
        return porResumen;
    }

    private VistaResumenTarjeta vista(Ciclo c, CalendarioTarjeta.Fechas fechas, ResumenTarjeta guardado,
            Map<YearMonth, List<VistaResumenTarjeta.Linea>> cuotasPorResumen) {
        LocalDate desde = c.calendario().desde(fechas.periodo());
        List<VistaResumenTarjeta.Linea> detalle = new ArrayList<>();
        for (Movimiento m : movimientos.comprasConTarjeta(c.tarjeta().getId(), desde.atStartOfDay(),
                fechas.cierre().plusDays(1).atStartOfDay())) {
            BigDecimal monto = m.isJuntada() ? Optional.ofNullable(movimientos.totalDeJuntada(m.getId()))
                    .orElse(m.getMonto()) : m.getMonto();
            detalle.add(linea(m, monto));
        }
        List<VistaResumenTarjeta.Linea> deCuotas = cuotasPorResumen.getOrDefault(fechas.periodo(), List.of());
        BigDecimal compras = suma(detalle);
        BigDecimal enCuotas = suma(deCuotas);
        detalle.addAll(deCuotas);
        BigDecimal cargado = compras.add(enCuotas);
        BigDecimal totalReal = guardado == null ? null : guardado.getTotalReal();
        return new VistaResumenTarjeta(guardado == null ? null : guardado.getId(), fechas.periodo(), desde,
                fechas.cierre(), fechas.vencimiento(), fechas.estimadas(),
                guardado == null ? EstadoResumenTarjeta.ABIERTO : guardado.getEstado(), compras, enCuotas, cargado,
                totalReal, totalReal == null ? null : diferencia(totalReal, cargado), c.tarjeta().getNombre(),
                cuentaDebito(c.tarjeta()), detalle);
    }

    private static VistaResumenTarjeta.Linea linea(Movimiento m, BigDecimal monto) {
        return new VistaResumenTarjeta.Linea(m.getId(), m.getFechaHora(), m.getDescripcion(),
                m.getCategoria() == null ? null : m.getCategoria().getNombre(),
                m.getCategoria() == null ? null : m.getCategoria().getEmoji(), monto, m.getCuotaActual(),
                m.getCuotaTotal(), m.isJuntada());
    }

    private static BigDecimal suma(List<VistaResumenTarjeta.Linea> lineas) {
        return lineas.stream().map(VistaResumenTarjeta.Linea::monto).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String cuentaDebito(TarjetaCredito tarjeta) {
        return tarjeta.getCuentaDebito() == null ? null : tarjeta.getCuentaDebito().getNombre();
    }

    private static ResumenTarjeta buscar(Ciclo c, Long id) {
        return c.guardados().stream().filter(r -> r.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No existe el resumen " + id));
    }

    /** El cerrado más viejo sin debitar. */
    private static ResumenTarjeta porPagar(Ciclo c) {
        return c.porPagar().stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("No hay ningún resumen cerrado sin debitar"));
    }
}
