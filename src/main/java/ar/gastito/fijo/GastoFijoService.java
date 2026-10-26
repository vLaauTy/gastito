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

    // --- Alta y baja ---

    /** Lo que hace falta para dar de alta un fijo. */
    public record NuevoFijo(String nombre, BigDecimal monto, Categoria categoria, MedioPago medioPago, Cuenta cuenta,
            TarjetaCredito tarjeta, int diaDelMes, Frecuencia frecuencia, boolean montoVariable) {
    }

    /**
     * Se empieza a cobrar este mes si todavía no pasó el día; si ya pasó, el mes que viene (no se crean cobros para
     * atrás).
     */
    @Transactional
    public VistaFijo alta(NuevoFijo f) {
        if (f.monto() == null || f.monto().signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser mayor a cero");
        }
        if (f.categoria() == null) {
            throw new IllegalArgumentException("Falta la categoría");
        }
        LocalDate hoy = LocalDate.now(clock);
        YearMonth inicio = primerMes(f.diaDelMes(), hoy);
        GastoFijo fijo = fijos.save(new GastoFijo(f.nombre(), f.monto().setScale(2, RoundingMode.HALF_UP),
                f.categoria(), f.medioPago(), f.cuenta(), f.tarjeta(), f.diaDelMes(), f.frecuencia(), inicio,
                f.montoVariable(), LocalDateTime.now(clock)));
        return vista(fijo, hoy);
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
        if (anterior == null || anterior.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal porcentaje = nuevo.subtract(anterior).multiply(CIEN).divide(anterior, 1, RoundingMode.HALF_UP);
        return porcentaje.abs().compareTo(UMBRAL_AUMENTO) > 0
                ? Optional.of(new Aumento(nombre, anterior, nuevo, porcentaje))
                : Optional.empty();
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
                f.getFrecuencia(), f.isMontoVariable(), f.isActivo(), f.proximoCobro(hoy));
    }

    private GastoFijo fijo(Long id) {
        return fijos.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el gasto fijo " + id));
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
