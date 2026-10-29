package ar.gastito.fijo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;

/**
 * Gastos fijos: cada mes (o cada dos), el día del cobro, se crea el movimiento pendiente y se le pregunta al
 * usuario si se cobró. Si la PC estuvo apagada ese día, se crea al arrancar. Se registran como gastos normales, con
 * el vínculo a su fijo.
 */
@Service
public class GastoFijoService {

    /** Para los variables, el monto propuesto es el promedio de estos últimos cobros confirmados. */
    static final int COBROS_PARA_PROMEDIAR = 3;
    /** Un cobro que difiere más que esto del anterior se avisa ("subió de $32.900 a $36.100"). */
    static final BigDecimal UMBRAL_AUMENTO = new BigDecimal("5");
    /** A qué hora queda el cobro generado (el día del cobro). */
    static final LocalTime HORA_DEL_COBRO = LocalTime.of(9, 0);

    private static final BigDecimal CIEN = new BigDecimal("100");

    private final GastoFijoRepository fijos;
    private final MovimientoRepository movimientos;
    private final Clock clock;

    public GastoFijoService(GastoFijoRepository fijos, MovimientoRepository movimientos, Clock clock) {
        this.fijos = fijos;
        this.movimientos = movimientos;
        this.clock = clock;
    }

    // --- Alta, edición y baja ---

    /**
     * Lo que hace falta para dar de alta un fijo.
     *
     * @param alias         nombres sueltos para reconocerlo en un mensaje ("edesur", "luz"), separados por coma, o
     *                      null
     * @param proponerMonto si sigue con el flujo automático de siempre (propone un monto para confirmar) o espera
     *                      que el usuario cargue el gasto y lo vincule por nombre o alias
     */
    public record NuevoFijo(String nombre, BigDecimal monto, Categoria categoria, MedioPago medioPago, Cuenta cuenta,
            TarjetaCredito tarjeta, int diaDelMes, Frecuencia frecuencia, boolean montoVariable, String alias,
            boolean proponerMonto) {

        public NuevoFijo(String nombre, BigDecimal monto, Categoria categoria, MedioPago medioPago, Cuenta cuenta,
                TarjetaCredito tarjeta, int diaDelMes, Frecuencia frecuencia, boolean montoVariable) {
            this(nombre, monto, categoria, medioPago, cuenta, tarjeta, diaDelMes, frecuencia, montoVariable, null,
                    true);
        }
    }

    /**
     * Se empieza a cobrar este mes si todavía no pasó el día; si ya pasó, el mes que viene (no se crean cobros para
     * atrás).
     */
    @Transactional
    public VistaFijo alta(NuevoFijo f) {
        validar(f);
        LocalDate hoy = LocalDate.now(clock);
        YearMonth inicio = primerMes(f.diaDelMes(), hoy);
        GastoFijo fijo = fijos.save(new GastoFijo(f.nombre(), f.monto().setScale(2, RoundingMode.HALF_UP),
                f.categoria(), f.medioPago(), f.cuenta(), f.tarjeta(), f.diaDelMes(), f.frecuencia(), inicio,
                f.montoVariable(), f.alias(), f.proponerMonto(), LocalDateTime.now(clock)));
        return vista(fijo, hoy);
    }

    /** ABM: cambia los datos de un fijo existente (el día del cobro y la frecuencia, también). */
    @Transactional
    public VistaFijo editar(Long fijoId, NuevoFijo f) {
        validar(f);
        GastoFijo fijo = fijo(fijoId);
        fijo.editar(f.nombre(), f.monto().setScale(2, RoundingMode.HALF_UP), f.categoria(), f.medioPago(),
                f.cuenta(), f.tarjeta(), f.diaDelMes(), f.frecuencia(), f.montoVariable(), f.alias(),
                f.proponerMonto());
        return vista(fijo, LocalDate.now(clock));
    }

    private static void validar(NuevoFijo f) {
        if (f.monto() == null || f.monto().signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        if (f.categoria() == null) {
            throw new IllegalArgumentException("Falta la categoría");
        }
    }

    /** Cuándo sería el primer cobro de un fijo que se da de alta hoy con ese día. */
    public LocalDate primerCobro(int diaDelMes) {
        LocalDate hoy = LocalDate.now(clock);
        YearMonth mes = primerMes(diaDelMes, hoy);
        return mes.atDay(Math.min(diaDelMes, mes.lengthOfMonth()));
    }

    /** El primer mes de cobro: este, si el día todavía no pasó (o es hoy); si no, el que viene. */
    static YearMonth primerMes(int diaDelMes, LocalDate hoy) {
        YearMonth este = YearMonth.from(hoy);
        return hoy.getDayOfMonth() <= Math.min(diaDelMes, este.lengthOfMonth()) ? este : este.plusMonths(1);
    }

    @Transactional
    public void darDeBaja(Long fijoId) {
        fijo(fijoId).darDeBaja();
    }

    // --- Cobros ---

    /**
     * Los cobros que tocan hasta hoy y todavía no están: el de hoy y, si la PC estuvo apagada, los de los días que
     * pasaron. De cada fijo se generan solo los posteriores al último mes registrado (o desde el mes de inicio).
     *
     * @return los cobros nuevos, pendientes, para preguntarle al usuario
     */
    @Transactional
    public List<Movimiento> generarCobros() {
        LocalDate hoy = LocalDate.now(clock);
        List<Movimiento> nuevos = new ArrayList<>();
        for (GastoFijo fijo : fijos.findByActivoTrueOrderByDiaDelMesAscNombreAsc()) {
            if (!fijo.isProponerMonto()) {
                continue;
            }
            Set<YearMonth> registrados = new HashSet<>();
            movimientos.periodosDeFijo(fijo.getId()).forEach(p -> registrados.add(YearMonth.from(p)));
            YearMonth desde = registrados.stream().max(YearMonth::compareTo).map(m -> m.plusMonths(1))
                    .filter(m -> m.isAfter(fijo.getMesInicio())).orElse(fijo.getMesInicio());
            for (YearMonth mes = desde; !mes.isAfter(YearMonth.from(hoy)); mes = mes.plusMonths(1)) {
                if (fijo.seCobraEn(mes) && !registrados.contains(mes) && !fijo.diaDeCobro(mes).isAfter(hoy)) {
                    nuevos.add(movimientos.save(Movimiento.cobroDeFijo(MovimientoInterpretado.conCategoria(
                            montoPropuesto(fijo), fijo.getNombre(), fijo.getCategoria(), fijo.getMedioPago(),
                            fijo.getCuenta(), fijo.getTarjeta(), fijo.diaDeCobro(mes).atTime(HORA_DEL_COBRO)),
                            fijo.getId(), mes.atDay(1), LocalDateTime.now(clock))));
                }
            }
        }
        return nuevos;
    }

    /** El monto que se propone: el esperado o, si es variable, el promedio de los últimos cobros confirmados. */
    BigDecimal montoPropuesto(GastoFijo fijo) {
        if (!fijo.isMontoVariable()) {
            return fijo.getMontoEsperado();
        }
        List<Movimiento> ultimos = movimientos.cobrosConfirmados(fijo.getId(),
                PageRequest.of(0, COBROS_PARA_PROMEDIAR));
        if (ultimos.isEmpty()) {
            return fijo.getMontoEsperado();
        }
        BigDecimal suma = ultimos.stream().map(Movimiento::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
        return suma.divide(BigDecimal.valueOf(ultimos.size()), 0, RoundingMode.HALF_UP).setScale(2);
    }

    /**
     * Un cobro que difiere mucho del anterior.
     *
     * @param porcentaje con un decimal; positivo si subió
     */
    public record Aumento(String nombre, BigDecimal anterior, BigDecimal nuevo, BigDecimal porcentaje) {

        public boolean subio() {
            return porcentaje.signum() > 0;
        }
    }

    /** Lo que quedó al confirmar un cobro. */
    public record Cobro(Movimiento movimiento, Optional<Aumento> aumento) {
    }

    /**
     * "Confirmar" o "Cambió el monto": el cobro queda guardado.
     *
     * @param monto el monto nuevo, o null si se confirma el propuesto
     */
    @Transactional
    public Cobro confirmar(Long movimientoId, BigDecimal monto) {
        Movimiento m = cobro(movimientoId);
        if (monto != null) {
            m.cambiarMontoDeCobro(monto.setScale(2, RoundingMode.HALF_UP));
        }
        m.confirmar();
        return new Cobro(m, alConfirmar(m));
    }

    /**
     * Después de confirmar un cobro (desde su tarjeta, o al unirlo con un gasto que se cargó a mano): el fijo
     * recuerda el monto y, si cambió más de un 5% contra el cobro anterior, se avisa.
     */
    @Transactional
    public Optional<Aumento> alConfirmar(Long movimientoId) {
        return alConfirmar(cobro(movimientoId));
    }

    private Optional<Aumento> alConfirmar(Movimiento m) {
        GastoFijo fijo = fijo(m.getGastoFijoId());
        fijo.cobrado(m.getMonto());
        Optional<Movimiento> anterior = movimientos.cobrosConfirmados(fijo.getId(), PageRequest.of(0, 12)).stream()
                .filter(c -> c.getFijoPeriodo().isBefore(m.getFijoPeriodo()))
                .findFirst();
        return anterior.flatMap(a -> aumento(fijo.getNombre(), a.getMonto(), m.getMonto()));
    }

    static Optional<Aumento> aumento(String nombre, BigDecimal anterior, BigDecimal nuevo) {
        BigDecimal porcentaje = variacion(anterior, nuevo);
        return porcentaje != null && porcentaje.abs().compareTo(UMBRAL_AUMENTO) > 0
                ? Optional.of(new Aumento(nombre, anterior, nuevo, porcentaje))
                : Optional.empty();
    }

    /** La diferencia porcentual contra el anterior, sin el umbral de aviso (para el checklist). Null sin con qué comparar. */
    static BigDecimal variacion(BigDecimal anterior, BigDecimal nuevo) {
        if (anterior == null || anterior.signum() == 0) {
            return null;
        }
        return nuevo.subtract(anterior).multiply(CIEN).divide(anterior, 1, RoundingMode.HALF_UP);
    }

    /** "Este mes no": el cobro se cancela y ese mes no se vuelve a pedir. */
    @Transactional
    public Movimiento esteMesNo(Long movimientoId) {
        Movimiento m = cobro(movimientoId);
        m.cancelar();
        return m;
    }

    /**
     * Para la etapa 7: el resumen de la tarjeta trae el cobro de un fijo y lo confirma solo, con el monto del
     * resumen. Si el cobro todavía no se había generado (el día no llegó, o la PC estaba apagada), se crea; si ya
     * estaba confirmado, queda como está.
     */
    @Transactional
    public Cobro confirmarDesdeResumen(Long fijoId, YearMonth mes, BigDecimal monto) {
        GastoFijo fijo = fijo(fijoId);
        Movimiento m = movimientos.findFirstByGastoFijoIdAndFijoPeriodo(fijoId, mes.atDay(1))
                .orElseGet(() -> movimientos.save(Movimiento.cobroDeFijo(MovimientoInterpretado.conCategoria(monto,
                        fijo.getNombre(), fijo.getCategoria(), fijo.getMedioPago(), fijo.getCuenta(),
                        fijo.getTarjeta(), fijo.diaDeCobro(mes).atTime(HORA_DEL_COBRO)), fijoId, mes.atDay(1),
                        LocalDateTime.now(clock))));
        if (m.getEstado() == EstadoMovimiento.CONFIRMADO) {
            return new Cobro(m, Optional.empty());
        }
        if (m.getEstado() == EstadoMovimiento.CANCELADO) {
            throw new IllegalStateException("El cobro de " + fijo.getNombre() + " de " + mes + " se marcó como "
                    + "\"este mes no\"");
        }
        return confirmar(m.getId(), monto);
    }

    // --- Consultas ---

    @Transactional(readOnly = true)
    public List<VistaFijo> activos() {
        LocalDate hoy = LocalDate.now(clock);
        return fijos.findByActivoTrueOrderByDiaDelMesAscNombreAsc().stream().map(f -> vista(f, hoy)).toList();
    }

    /** Lo que se cargó en un mes, para el historial de un fijo. */
    public record MesHistorial(YearMonth mes, BigDecimal monto) {
    }

    /** Los últimos 12 meses cargados de un fijo (confirmados), el más nuevo primero. */
    @Transactional(readOnly = true)
    public List<MesHistorial> historial(Long fijoId) {
        return movimientos.cobrosConfirmados(fijoId, PageRequest.of(0, 12)).stream()
                .map(m -> new MesHistorial(YearMonth.from(m.getFijoPeriodo()), m.getMonto())).toList();
    }

    /** ABM: todos, activos e inactivos. */
    @Transactional(readOnly = true)
    public List<VistaFijo> todos() {
        LocalDate hoy = LocalDate.now(clock);
        return fijos.findAllByOrderByNombreAsc().stream().map(f -> vista(f, hoy)).toList();
    }

    /** Para el ABM de la web: los datos con los ids del catálogo, no solo los nombres. */
    public record VistaAbm(Long id, String nombre, BigDecimal montoEsperado, Categoria categoria,
            MedioPago medioPago, Cuenta cuenta, TarjetaCredito tarjeta, int diaDelMes, Frecuencia frecuencia,
            boolean montoVariable, String alias, boolean proponerMonto, boolean activo) {
    }

    @Transactional(readOnly = true)
    public List<VistaAbm> todosParaAbm() {
        return fijos.findAllByOrderByNombreAsc().stream()
                .map(f -> new VistaAbm(f.getId(), f.getNombre(), f.getMontoEsperado(), f.getCategoria(),
                        f.getMedioPago(), f.getCuenta(), f.getTarjeta(), f.getDiaDelMes(), f.getFrecuencia(),
                        f.isMontoVariable(), f.getAlias(), f.isProponerMonto(), f.isActivo()))
                .toList();
    }

    @Transactional(readOnly = true)
    public VistaFijo vista(Long fijoId) {
        return vista(fijo(fijoId), LocalDate.now(clock));
    }

    /** Los cobros generados que esperan respuesta (por si se perdió el mensaje). */
    @Transactional(readOnly = true)
    public List<Movimiento> cobrosPendientes() {
        return movimientos.cobrosDeFijosPendientes();
    }

    private VistaFijo vista(GastoFijo f, LocalDate hoy) {
        String billetera = f.getCuenta() != null ? f.getCuenta().getNombre()
                : f.getTarjeta() != null ? f.getTarjeta().getNombre()
                        : f.getMedioPago() != null ? f.getMedioPago().getNombre() : null;
        return new VistaFijo(f.getId(), f.getNombre(), f.getMontoEsperado(), montoPropuesto(f),
                f.getCategoria().getNombre(), f.getCategoria().getEmoji(), billetera, f.getDiaDelMes(),
                f.getFrecuencia(), f.isMontoVariable(), f.getAlias(), f.isProponerMonto(), f.isActivo(),
                f.proximoCobro(hoy));
    }

    private GastoFijo fijo(Long id) {
        return fijos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el gasto fijo " + id));
    }

    // --- Vínculo con un gasto (texto, audio o resumen de la tarjeta en la etapa 7) ---

    /** El fijo activo cuyo nombre o algún alias aparece en el texto, si hay uno. */
    @Transactional(readOnly = true)
    public Optional<GastoFijo> vincular(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        return fijos.findByActivoTrueOrderByDiaDelMesAscNombreAsc().stream().filter(f -> f.coincideCon(texto))
                .findFirst();
    }

    /** Vincula un gasto pendiente (ya creado) a su fijo, para el mes de su fecha. */
    @Transactional
    public void vincular(Movimiento pendiente, GastoFijo fijo) {
        pendiente.vincularFijo(fijo.getId(), YearMonth.from(pendiente.getFechaHora()).atDay(1));
    }

    /** Si ese fijo ya tiene un cobro confirmado ese mes ("Ya cargaste la luz este mes"). */
    @Transactional(readOnly = true)
    public Optional<Movimiento> yaCargadoEstePeriodo(Long fijoId, YearMonth mes) {
        return movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijoId, mes.atDay(1)).stream()
                .filter(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO).findFirst();
    }

    // --- Checklist mensual ---

    public enum EstadoChecklist {
        FALTA, CARGADO, ESTE_MES_NO
    }

    /**
     * Una fila del checklist de un mes.
     *
     * @param monto         con CARGADO, lo que se cargó
     * @param fecha         con CARGADO, cuándo se cargó
     * @param montoAnterior el monto del período anterior (de referencia si FALTA, para la diferencia si CARGADO)
     * @param porcentaje    con CARGADO y si hay anterior, la diferencia (puede ser negativa)
     * @param vencimiento   el día aproximado del cobro de este mes
     * @param movimientoId  con CARGADO, el gasto (para corregirle el monto)
     * @param estimado      con CARGADO, si se tildó con el monto aproximado y falta el real
     * @param aproximado    con FALTA, con cuánto se tilda "Pagado"
     */
    public record ItemChecklist(Long fijoId, String nombre, String emoji, EstadoChecklist estado, BigDecimal monto,
            LocalDate fecha, BigDecimal montoAnterior, BigDecimal porcentaje, LocalDate vencimiento,
            boolean proponerMonto, Long movimientoId, boolean estimado, BigDecimal aproximado) {
    }

    /** Los fijos activos que corresponden a ese mes (los bimestrales, solo en los suyos). */
    @Transactional(readOnly = true)
    public List<ItemChecklist> checklist(YearMonth mes) {
        List<ItemChecklist> items = new ArrayList<>();
        for (GastoFijo fijo : fijos.findByActivoTrueOrderByDiaDelMesAscNombreAsc()) {
            if (fijo.seCobraEn(mes)) {
                items.add(itemChecklist(fijo, mes));
            }
        }
        return items;
    }

    private ItemChecklist itemChecklist(GastoFijo fijo, YearMonth mes) {
        LocalDate vencimiento = fijo.diaDeCobro(mes);
        List<Movimiento> delPeriodo = movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijo.getId(),
                mes.atDay(1));
        Optional<Movimiento> confirmado = delPeriodo.stream()
                .filter(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO).findFirst();
        if (confirmado.isPresent()) {
            Movimiento m = confirmado.get();
            BigDecimal anterior = ultimoConfirmadoAntesDe(fijo.getId(), mes).map(Movimiento::getMonto).orElse(null);
            return new ItemChecklist(fijo.getId(), fijo.getNombre(), fijo.getCategoria().getEmoji(),
                    EstadoChecklist.CARGADO, m.getMonto(), m.getFechaHora().toLocalDate(), anterior,
                    anterior == null ? null : variacion(anterior, m.getMonto()), vencimiento, fijo.isProponerMonto(),
                    m.getId(), m.isMontoEstimado(), null);
        }
        if (delPeriodo.stream().anyMatch(m -> m.getEstado() == EstadoMovimiento.CANCELADO)) {
            return new ItemChecklist(fijo.getId(), fijo.getNombre(), fijo.getCategoria().getEmoji(),
                    EstadoChecklist.ESTE_MES_NO, null, null, null, null, vencimiento, fijo.isProponerMonto(), null,
                    false, null);
        }
        BigDecimal anterior = ultimoConfirmadoAntesDe(fijo.getId(), mes.plusMonths(1)).map(Movimiento::getMonto)
                .orElse(null);
        return new ItemChecklist(fijo.getId(), fijo.getNombre(), fijo.getCategoria().getEmoji(),
                EstadoChecklist.FALTA, null, null, anterior, null, vencimiento, fijo.isProponerMonto(), null, false,
                fijo.montoAproximado(anterior));
    }

    private Optional<Movimiento> ultimoConfirmadoAntesDe(Long fijoId, YearMonth mes) {
        return movimientos.cobrosConfirmados(fijoId, PageRequest.of(0, 12)).stream()
                .filter(c -> c.getFijoPeriodo().isBefore(mes.atDay(1))).findFirst();
    }

    /** Lo que quedó al cargar un fijo a mano. */
    public record Carga(Movimiento movimiento, Optional<Movimiento> yaCargado) {
    }

    /**
     * El usuario dice cuánto gastó en un fijo (por texto, audio o desde la web): se guarda confirmado, ya vinculado
     * al mes de la fecha. Si ya había uno confirmado ese mes, se avisa pero se guarda igual (puede ser otro pago).
     */
    @Transactional
    public Carga cargarManual(Long fijoId, BigDecimal monto, LocalDateTime fechaHora, Origen origen,
            String textoOriginal) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        GastoFijo fijo = fijo(fijoId);
        LocalDate periodo = YearMonth.from(fechaHora).atDay(1);
        Optional<Movimiento> yaCargado = movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijoId, periodo)
                .stream().filter(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO).findFirst();
        Movimiento nuevo = movimientos.save(Movimiento.cobroManualDeFijo(MovimientoInterpretado.conCategoria(
                monto.setScale(2, RoundingMode.HALF_UP), fijo.getNombre(), fijo.getCategoria(), fijo.getMedioPago(),
                fijo.getCuenta(), fijo.getTarjeta(), fechaHora), fijoId, periodo, origen, textoOriginal,
                LocalDateTime.now(clock)));
        nuevo.confirmar();
        fijo.cobrado(nuevo.getMonto());
        return new Carga(nuevo, yaCargado);
    }

    /** "Este mes no" desde el checklist (sin que se haya generado el cobro automático todavía). */
    @Transactional
    public void esteMesNoDelPeriodo(Long fijoId, YearMonth mes) {
        GastoFijo fijo = fijo(fijoId);
        List<Movimiento> delPeriodo = movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijoId, mes.atDay(1));
        Optional<Movimiento> pendiente = delPeriodo.stream().filter(Movimiento::isPendiente).findFirst();
        if (pendiente.isPresent()) {
            pendiente.get().cancelar();
            return;
        }
        if (delPeriodo.stream().anyMatch(m -> m.getEstado() == EstadoMovimiento.CONFIRMADO)) {
            throw new IllegalStateException("Ya cargaste " + fijo.getNombre() + " este mes");
        }
        if (!delPeriodo.isEmpty()) {
            return; // ya estaba "este mes no"
        }
        Movimiento placeholder = movimientos.save(Movimiento.cobroDeFijo(MovimientoInterpretado.conCategoria(
                fijo.getMontoEsperado(), fijo.getNombre(), fijo.getCategoria(), fijo.getMedioPago(), fijo.getCuenta(),
                fijo.getTarjeta(), mes.atDay(1).atTime(HORA_DEL_COBRO)), fijoId, mes.atDay(1),
                LocalDateTime.now(clock)));
        placeholder.cancelar();
    }

    /** Deshace "este mes no" o una carga manual, y el fijo vuelve a "Falta". No se puede sobre un cobro automático pendiente. */
    @Transactional
    public void deshacer(Long fijoId, YearMonth mes) {
        List<Movimiento> delPeriodo = movimientos.findByGastoFijoIdAndFijoPeriodoOrderByIdDesc(fijoId, mes.atDay(1));
        if (delPeriodo.isEmpty()) {
            throw new NoSuchElementException("No hay nada que deshacer en " + mes);
        }
        Movimiento ultimo = delPeriodo.get(0);
        if (ultimo.isPendiente()) {
            throw new IllegalStateException("Ese cobro todavía está esperando que lo confirmes");
        }
        movimientos.delete(ultimo);
    }

    private Movimiento cobro(Long movimientoId) {
        Movimiento m = movimientos.findById(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + movimientoId));
        if (m.getGastoFijoId() == null) {
            throw new IllegalStateException("El movimiento " + movimientoId + " no es el cobro de un gasto fijo");
        }
        return m;
    }
}
