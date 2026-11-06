package ar.gastito.inversion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.TipoCuenta;
import ar.gastito.inversion.CalculoInversion.Aporte;
import ar.gastito.inversion.CalculoInversion.Clave;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;

/**
 * Cómo están las inversiones: por cuenta y por posición, lo puesto, lo que vale, el rendimiento, cuándo se actualizó,
 * lo reservado para metas, el historial y la serie para el gráfico. Lo usan el bot, el dashboard y el resumen; todo
 * se calcula acá (el frontend no calcula nada).
 */
@Service
public class PanelInversiones {

    /** El nombre de la bolsa cuando la cuenta tiene posiciones: la plata que no está en ninguna. */
    public static final String SIN_INVERTIR = "Sin invertir";

    private final CuentaRepository cuentas;
    private final PosicionInversionRepository posiciones;
    private final OperacionInversionRepository operaciones;
    private final ReservaInversionRepository reservas;
    private final MovimientoRepository movimientos;
    private final FuenteDeValuaciones fuente;
    private final Clock clock;

    public PanelInversiones(CuentaRepository cuentas, PosicionInversionRepository posiciones,
            OperacionInversionRepository operaciones, ReservaInversionRepository reservas,
            MovimientoRepository movimientos, FuenteDeValuaciones fuente, Clock clock) {
        this.cuentas = cuentas;
        this.posiciones = posiciones;
        this.operaciones = operaciones;
        this.reservas = reservas;
        this.movimientos = movimientos;
        this.fuente = fuente;
        this.clock = clock;
    }

    /**
     * La bolsa de la cuenta (posicionId null) o una posición.
     *
     * @param nombre el de la posición; el de la cuenta si no tiene posiciones, o "Sin invertir"
     */
    public record Tenencia(Long posicionId, String nombre, BigDecimal aportado, BigDecimal valor,
            BigDecimal rendimiento, BigDecimal porcentaje, boolean sinValuar, LocalDate ultimaValuacion) {
    }

    public record Reserva(Long id, String meta, BigDecimal monto) {
    }

    /**
     * @param actualizado      el último valor real (manual o "igual que antes"); si nunca se valuó, el día del primer
     *                         aporte (vale lo que se puso). Null si la cuenta está vacía.
     * @param diasSinActualizar desde actualizado hasta hoy, o null
     * @param libre            lo que vale menos lo reservado
     */
    public record EstadoCuenta(Long id, String nombre, String alias, Integer recordatorioDias, BigDecimal aportado,
            BigDecimal valor, BigDecimal rendimiento, BigDecimal porcentaje, LocalDate actualizado,
            Long diasSinActualizar, List<Tenencia> tenencias, BigDecimal reservado, BigDecimal libre,
            List<Reserva> reservas) {

        /** Las tenencias que son posiciones (sin la bolsa). */
        public List<Tenencia> posiciones() {
            return tenencias.stream().filter(t -> t.posicionId() != null).toList();
        }

        public Optional<Tenencia> bolsa() {
            return tenencias.stream().filter(t -> t.posicionId() == null).findFirst();
        }
    }

    /**
     * Una línea del historial: un aporte o un retiro (un movimiento entre cuentas), o un saldo inicial, una compra o
     * una venta.
     *
     * @param clave      "m:<id>" si es un movimiento, "o:<id>" si es una operación (para borrarla)
     * @param tipo       APORTE, RETIRO, SALDO_INICIAL, COMPRA o VENTA
     * @param otraCuenta en un aporte, de dónde salió; en un retiro, a dónde fue ("Efectivo" si no tiene cuenta)
     */
    public record Operacion(String clave, LocalDate fecha, String tipo, Long cuentaId, String cuenta, String posicion,
            String otraCuenta, BigDecimal monto) {
    }

    public record Valuacion(Long id, LocalDate fecha, Long cuentaId, String cuenta, String posicion, BigDecimal valor,
            OrigenValuacion origen) {
    }

    /**
     * Todo lo invertido.
     *
     * @param valor              lo que vale hoy, entre todas las cuentas
     * @param actualizado        la actualización más vieja entre las cuentas (la que más hace que no se mira)
     * @param valuaciones        de la más nueva a la más vieja
     * @param historial          del más nuevo al más viejo
     */
    public record Panel(BigDecimal valor, BigDecimal aportado, BigDecimal rendimiento, BigDecimal porcentaje,
            LocalDate actualizado, Long diasSinActualizar, BigDecimal reservado, List<EstadoCuenta> cuentas,
            List<Operacion> historial, List<Valuacion> valuaciones, List<CalculoInversion.Punto> serie) {
    }

    /** Lo que hace falta de una cuenta para calcular: sus aportes, sus valuaciones y sus posiciones. */
    record Datos(Cuenta cuenta, List<PosicionInversion> posiciones, List<Aporte> aportes,
            List<FuenteDeValuaciones.Valuacion> valuaciones, List<OperacionInversion> operaciones,
            List<Movimiento> movimientos) {

        List<Clave> claves() {
            List<Clave> claves = new ArrayList<>();
            claves.add(new Clave(cuenta.getId(), null));
            posiciones.forEach(p -> claves.add(new Clave(cuenta.getId(), p.getId())));
            return claves;
        }
    }

    @Transactional(readOnly = true)
    public Panel panel() {
        LocalDate hoy = LocalDate.now(clock);
        List<Movimiento> deInversiones = movimientos.deInversiones();
        List<EstadoCuenta> estados = new ArrayList<>();
        List<Aporte> aportes = new ArrayList<>();
        List<FuenteDeValuaciones.Valuacion> valuaciones = new ArrayList<>();
        List<Clave> claves = new ArrayList<>();
        List<Operacion> historial = new ArrayList<>();
        List<Valuacion> lineas = new ArrayList<>();
        for (Cuenta c : cuentas.findByActivaTrueAndTipoOrderById(TipoCuenta.INVERSION)) {
            Datos d = datos(c, deInversiones);
            estados.add(estado(d, hoy));
            aportes.addAll(d.aportes());
            valuaciones.addAll(d.valuaciones());
            claves.addAll(d.claves());
            historial.addAll(historial(d));
            lineas.addAll(valuaciones(d));
        }
        valuaciones.sort(Comparator.comparing(FuenteDeValuaciones.Valuacion::fecha)
                .thenComparing(FuenteDeValuaciones.Valuacion::creadaEn)
                .thenComparing(FuenteDeValuaciones.Valuacion::id));
        historial.sort(Comparator.comparing(Operacion::fecha).thenComparing(Operacion::clave).reversed());
        lineas.sort(Comparator.comparing(Valuacion::fecha).thenComparing(Valuacion::id).reversed());

        BigDecimal valor = suma(estados.stream().map(EstadoCuenta::valor).toList());
        BigDecimal aportado = suma(estados.stream().map(EstadoCuenta::aportado).toList());
        CalculoInversion.Rendimiento r = CalculoInversion.rendimiento(valor, aportado);
        LocalDate actualizado = estados.stream().map(EstadoCuenta::actualizado).filter(f -> f != null)
                .min(Comparator.naturalOrder()).orElse(null);
        return new Panel(valor, aportado, r.pesos(), r.porcentaje(), actualizado, dias(actualizado, hoy),
                suma(estados.stream().map(EstadoCuenta::reservado).toList()), estados, historial, lineas,
                CalculoInversion.serie(claves, aportes, valuaciones));
    }

    /** Una cuenta de inversión, como está hoy. */
    @Transactional(readOnly = true)
    public EstadoCuenta cuenta(long cuentaId) {
        Cuenta c = cuentas.findById(cuentaId)
                .filter(Cuenta::isInversion)
                .orElseThrow(() -> new IllegalArgumentException("No existe la cuenta de inversión " + cuentaId));
        return estado(datos(c, movimientos.deInversiones()), LocalDate.now(clock));
    }

    /** Lo que vale hoy todo lo invertido (sin las cuentas dadas de baja). */
    @Transactional(readOnly = true)
    public BigDecimal totalInvertido() {
        return panel().valor();
    }

    /** Si hay alguna cuenta de inversión activa. */
    @Transactional(readOnly = true)
    public boolean hayInversiones() {
        return !cuentas.findByActivaTrueAndTipoOrderById(TipoCuenta.INVERSION).isEmpty();
    }

    /**
     * Lo que se pasó a las inversiones menos lo que se sacó de ellas entre dos momentos: esa plata salió (o volvió)
     * del disponible, así que no se cuenta dos veces al sumarlos.
     */
    @Transactional(readOnly = true)
    public BigDecimal aportesNetos(LocalDateTime desde, LocalDateTime hasta) {
        BigDecimal neto = BigDecimal.ZERO;
        for (Movimiento m : movimientos.deInversiones()) {
            if (m.getFechaHora().isBefore(desde) || !m.getFechaHora().isBefore(hasta)) {
                continue;
            }
            boolean entra = m.getCuentaDestino() != null && m.getCuentaDestino().isInversion();
            boolean sale = m.getCuenta() != null && m.getCuenta().isInversion();
            if (entra && !sale) {
                neto = neto.add(m.getMonto());
            } else if (sale && !entra) {
                neto = neto.subtract(m.getMonto());
            }
        }
        return CalculoInversion.pesos(neto);
    }

    Datos datos(Cuenta c, List<Movimiento> deInversiones) {
        long id = c.getId();
        List<PosicionInversion> activas = posiciones.findByCuentaIdOrderById(id).stream()
                .filter(PosicionInversion::isActiva).toList();
        List<OperacionInversion> ops = operaciones.findByCuentaIdOrderByFechaAscIdAsc(id);
        List<Movimiento> movs = deInversiones.stream().filter(m -> esDe(m, id)).toList();
        List<Aporte> aportes = new ArrayList<>();
        Clave bolsa = new Clave(id, null);
        for (OperacionInversion o : ops) {
            Clave posicion = o.getPosicion() == null ? bolsa : new Clave(id, o.getPosicion().getId());
            switch (o.getTipo()) {
                case SALDO_INICIAL -> aportes.add(new Aporte(posicion, o.getFecha(), o.getMonto()));
                case COMPRA -> {
                    aportes.add(new Aporte(bolsa, o.getFecha(), o.getMonto().negate()));
                    aportes.add(new Aporte(posicion, o.getFecha(), o.getMonto()));
                }
                case VENTA -> {
                    aportes.add(new Aporte(posicion, o.getFecha(), o.getMonto().negate()));
                    aportes.add(new Aporte(bolsa, o.getFecha(), o.getMonto()));
                }
            }
        }
        for (Movimiento m : movs) {
            LocalDate fecha = m.getFechaHora().toLocalDate();
            if (m.getCuentaDestino() != null && m.getCuentaDestino().getId() == id) {
                aportes.add(new Aporte(bolsa, fecha, m.getMonto()));
            }
            if (m.getCuenta() != null && m.getCuenta().getId() == id) {
                aportes.add(new Aporte(bolsa, fecha, m.getMonto().negate()));
            }
        }
        return new Datos(c, activas, aportes, fuente.deLaCuenta(id), ops, movs);
    }

    private static boolean esDe(Movimiento m, long cuentaId) {
        return (m.getCuenta() != null && m.getCuenta().getId() == cuentaId)
                || (m.getCuentaDestino() != null && m.getCuentaDestino().getId() == cuentaId);
    }

    EstadoCuenta estado(Datos d, LocalDate hoy) {
        List<Tenencia> tenencias = new ArrayList<>();
        BigDecimal aportado = BigDecimal.ZERO;
        BigDecimal valor = BigDecimal.ZERO;
        LocalDate actualizado = null;
        boolean algunaValuada = false;
        for (Clave clave : d.claves()) {
            CalculoInversion.Estado e = CalculoInversion.estado(clave, d.aportes(), d.valuaciones(), hoy);
            aportado = aportado.add(e.aportado());
            valor = valor.add(e.valor());
            if (e.ultimaActualizacion() != null && (actualizado == null || e.ultimaActualizacion().isAfter(actualizado))) {
                actualizado = e.ultimaActualizacion();
            }
            algunaValuada |= !e.sinValuar();
            boolean esBolsa = clave.posicionId() == null;
            if (esBolsa && !d.posiciones().isEmpty() && e.aportado().signum() == 0 && e.valor().signum() == 0) {
                continue;
            }
            String nombre = !esBolsa ? d.posiciones().stream().filter(p -> p.getId().equals(clave.posicionId()))
                    .findFirst().map(PosicionInversion::getNombre).orElse("?")
                    : d.posiciones().isEmpty() ? d.cuenta().getNombre() : SIN_INVERTIR;
            CalculoInversion.Rendimiento r = e.rendimiento();
            tenencias.add(new Tenencia(clave.posicionId(), nombre, e.aportado(), e.valor(), r.pesos(), r.porcentaje(),
                    e.sinValuar(), e.ultimaValuacion()));
        }
        if (actualizado == null && !algunaValuada) {
            actualizado = d.aportes().stream().map(Aporte::fecha).min(Comparator.naturalOrder()).orElse(null);
        }
        List<Reserva> lasReservas = reservas.findByCuentaIdAndLiberadaEnIsNullOrderById(d.cuenta().getId()).stream()
                .map(r -> new Reserva(r.getId(), r.getMeta(), CalculoInversion.pesos(r.getMonto()))).toList();
        BigDecimal reservado = suma(lasReservas.stream().map(Reserva::monto).toList());
        CalculoInversion.Rendimiento r = CalculoInversion.rendimiento(valor, aportado);
        return new EstadoCuenta(d.cuenta().getId(), d.cuenta().getNombre(), d.cuenta().getPalabrasClave(),
                d.cuenta().getRecordatorioDias(), CalculoInversion.pesos(aportado), CalculoInversion.pesos(valor),
                r.pesos(), r.porcentaje(), actualizado, dias(actualizado, hoy), tenencias, reservado,
                CalculoInversion.pesos(valor.subtract(reservado)), lasReservas);
    }

    private static List<Operacion> historial(Datos d) {
        List<Operacion> lineas = new ArrayList<>();
        Cuenta c = d.cuenta();
        for (OperacionInversion o : d.operaciones()) {
            lineas.add(new Operacion("o:" + o.getId(), o.getFecha(), o.getTipo().name(), c.getId(), c.getNombre(),
                    o.getPosicion() == null ? null : o.getPosicion().getNombre(), null,
                    CalculoInversion.pesos(o.getMonto())));
        }
        for (Movimiento m : d.movimientos()) {
            boolean entra = m.getCuentaDestino() != null && m.getCuentaDestino().getId().equals(c.getId());
            Cuenta otra = entra ? m.getCuenta() : m.getCuentaDestino();
            lineas.add(new Operacion("m:" + m.getId(), m.getFechaHora().toLocalDate(), entra ? "APORTE" : "RETIRO",
                    c.getId(), c.getNombre(), null, otra == null ? "Efectivo" : otra.getNombre(),
                    CalculoInversion.pesos(m.getMonto())));
        }
        return lineas;
    }

    private static List<Valuacion> valuaciones(Datos d) {
        return d.valuaciones().stream().map(v -> new Valuacion(v.id(), v.fecha(), d.cuenta().getId(),
                d.cuenta().getNombre(), v.posicionId() == null ? null : d.posiciones().stream()
                        .filter(p -> p.getId().equals(v.posicionId())).findFirst()
                        .map(PosicionInversion::getNombre).orElse(null),
                CalculoInversion.pesos(v.valor()), v.origen())).toList();
    }

    private static Long dias(LocalDate desde, LocalDate hoy) {
        return desde == null ? null : ChronoUnit.DAYS.between(desde, hoy);
    }

    private static BigDecimal suma(List<BigDecimal> montos) {
        return CalculoInversion.pesos(montos.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
