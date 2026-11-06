package ar.gastito.inversion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.CuentaRepository;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.MedioPagoRepository;
import ar.gastito.catalogo.TipoCuenta;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoRepository;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.TipoMovimiento;

/**
 * Lo que se carga de las inversiones: cuentas y posiciones, el saldo inicial (ahorro previo, no es un ingreso), los
 * aportes y retiros (movimientos ENTRE_CUENTAS: no son gastos ni ingresos), las compras y ventas dentro de una cuenta,
 * las valuaciones y las reservas para metas. Cuando un aporte, un retiro o una compra mueve una tenencia que ya tenía
 * valor, queda una valuación de ajuste (el valor anterior más o menos lo movido), así el rendimiento no cambia solo
 * por poner o sacar plata.
 */
@Service
@Transactional
public class InversionService {

    private final CuentaRepository cuentas;
    private final PosicionInversionRepository posiciones;
    private final OperacionInversionRepository operaciones;
    private final ReservaInversionRepository reservas;
    private final ValuacionesManuales valuaciones;
    private final MovimientoRepository movimientos;
    private final MedioPagoRepository medios;
    private final PanelInversiones panel;
    private final InversionesProperties properties;
    private final Clock clock;

    public InversionService(CuentaRepository cuentas, PosicionInversionRepository posiciones,
            OperacionInversionRepository operaciones, ReservaInversionRepository reservas,
            ValuacionesManuales valuaciones, MovimientoRepository movimientos, MedioPagoRepository medios,
            PanelInversiones panel, InversionesProperties properties, Clock clock) {
        this.cuentas = cuentas;
        this.posiciones = posiciones;
        this.operaciones = operaciones;
        this.reservas = reservas;
        this.valuaciones = valuaciones;
        this.movimientos = movimientos;
        this.medios = medios;
        this.panel = panel;
        this.properties = properties;
        this.clock = clock;
    }

    // --- Cuentas y posiciones ---

    /** Una cuenta de inversión nueva, con el recordatorio por defecto. */
    public Cuenta crearCuenta(String nombre, String alias) {
        if (nombre != null && cuentas.existsByNombreIgnoreCase(nombre.strip())) {
            throw new IllegalArgumentException("Ya hay una cuenta que se llama " + nombre.strip());
        }
        return cuentas.save(Cuenta.deInversion(nombre, alias,
                properties.diasRecordatorio() > 0 ? properties.diasRecordatorio() : null));
    }

    public Cuenta editarCuenta(long id, String nombre, String alias, Integer recordatorioDias) {
        Cuenta c = deInversion(id);
        if (nombre != null && !nombre.strip().equalsIgnoreCase(c.getNombre())
                && cuentas.existsByNombreIgnoreCase(nombre.strip())) {
            throw new IllegalArgumentException("Ya hay una cuenta que se llama " + nombre.strip());
        }
        c.editar(nombre, alias);
        c.cambiarRecordatorio(recordatorioDias);
        return c;
    }

    /** Dar de baja no borra el historial: la cuenta deja de sumar y de aparecer. */
    public void darDeBaja(long id) {
        deInversion(id).darDeBaja();
    }

    /** Cada cuántos días preguntar cuánto vale (null o 0: no preguntar más). */
    public Cuenta cambiarRecordatorio(long cuentaId, Integer dias) {
        Cuenta c = deInversion(cuentaId);
        c.cambiarRecordatorio(dias);
        return c;
    }

    /** La posición con ese nombre en la cuenta (sin distinguir mayúsculas ni tildes), o una nueva. */
    public PosicionInversion posicion(long cuentaId, String nombre) {
        Cuenta c = deInversion(cuentaId);
        return posiciones.findByCuentaIdOrderById(cuentaId).stream()
                .filter(p -> NombresInversion.mismaPosicion(p, nombre))
                .findFirst()
                .map(p -> {
                    if (!p.isActiva()) {
                        throw new IllegalArgumentException("La posición " + p.getNombre() + " está dada de baja");
                    }
                    return p;
                })
                .orElseGet(() -> posiciones.save(new PosicionInversion(c, NombresInversion.limpiar(nombre),
                        NombresInversion.palabrasClave(nombre), ahora())));
    }

    /** La posición deja de aparecer (lo que tenía queda en el historial). */
    public void darDeBajaPosicion(long posicionId) {
        PosicionInversion p = posicionPorId(posicionId);
        p.darDeBaja();
    }

    // --- Saldo inicial ---

    /** El saldo inicial de una posición (nombre) o de la bolsa de la cuenta (nombre null). */
    public record SaldoPedido(String posicion, BigDecimal monto) {
    }

    /** Un saldo inicial que ya estaba y se reemplazó: de qué posición (null, la bolsa) y cuánto era. */
    public record SaldoAnterior(String posicion, BigDecimal monto, LocalDate fecha) {
    }

    /** @param reemplazados los saldos iniciales que ya había y se cambiaron */
    public record CargaInicial(Cuenta cuenta, List<OperacionInversion> saldos, List<SaldoAnterior> reemplazados,
            boolean cuentaNueva) {
    }

    /**
     * "Tengo 850.000 invertidos en Cocos, 500.000 en CEDEAR SPY y 350.000 en un FCI": el ahorro previo, con fecha.
     * No es un ingreso. Crea la cuenta y las posiciones que no existen; la bolsa queda con el total menos las
     * posiciones. Si ya había un saldo inicial de esa bolsa o posición, se reemplaza.
     *
     * @param cuentaId  la cuenta, o null para buscarla o crearla por nombre
     * @param total     el total de la cuenta, o null si solo se dijeron las posiciones
     */
    public CargaInicial cargarSaldoInicial(Long cuentaId, String nombreCuenta, BigDecimal total,
            List<SaldoPedido> pedidos, LocalDate fecha, String texto) {
        boolean nueva = false;
        Cuenta c;
        if (cuentaId != null) {
            c = deInversion(cuentaId);
        } else {
            Optional<Cuenta> existente = porNombre(nombreCuenta);
            nueva = existente.isEmpty();
            c = existente.orElseGet(() -> crearCuenta(nombreCuenta, null));
        }
        List<SaldoPedido> deLasPosiciones = pedidos.stream().filter(p -> p.posicion() != null).toList();
        BigDecimal enPosiciones = deLasPosiciones.stream().map(SaldoPedido::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal bolsa = pedidos.stream().filter(p -> p.posicion() == null).map(SaldoPedido::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total != null) {
            if (total.compareTo(enPosiciones) < 0) {
                throw new IllegalArgumentException("Las posiciones suman más que el total de " + c.getNombre());
            }
            bolsa = total.subtract(enPosiciones);
        }
        List<OperacionInversion> saldos = new ArrayList<>();
        List<SaldoAnterior> reemplazados = new ArrayList<>();
        if (bolsa.signum() > 0 || (total != null && deLasPosiciones.isEmpty())) {
            saldo(c, null, bolsa, fecha, texto, saldos, reemplazados);
        }
        for (SaldoPedido p : deLasPosiciones) {
            saldo(c, posicion(c.getId(), p.posicion()), p.monto(), fecha, texto, saldos, reemplazados);
        }
        return new CargaInicial(c, saldos, reemplazados, nueva);
    }

    /** El saldo inicial de una cuenta (existente por id, o por nombre: se busca o se crea). */
    public record SaldoDeCuenta(Long cuentaId, String nombreCuenta, BigDecimal total, List<SaldoPedido> posiciones) {
    }

    /**
     * Los saldos iniciales de varias cuentas dichos en un mismo mensaje ("505.133 en la reserva de Mercado Pago y
     * 680.000 en acciones en Cocos"): todos o ninguno.
     */
    public List<CargaInicial> cargarSaldosIniciales(List<SaldoDeCuenta> cuentas, LocalDate fecha, String texto) {
        return cuentas.stream().map(s -> cargarSaldoInicial(s.cuentaId(), s.nombreCuenta(), s.total(),
                s.posiciones(), fecha, texto)).toList();
    }

    /** Desde el dashboard: el saldo inicial de la bolsa (posicionId null) o de una posición; reemplaza al anterior. */
    public CargaInicial cargarSaldo(long cuentaId, Long posicionId, BigDecimal monto, LocalDate fecha) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El saldo inicial tiene que ser mayor a cero");
        }
        Cuenta c = deInversion(cuentaId);
        PosicionInversion p = posicionId == null ? null : posicionPorId(posicionId);
        if (p != null && !p.getCuenta().getId().equals(c.getId())) {
            throw new IllegalArgumentException("La posición " + p.getNombre() + " no es de " + c.getNombre());
        }
        List<OperacionInversion> saldos = new ArrayList<>();
        List<SaldoAnterior> reemplazados = new ArrayList<>();
        saldo(c, p, monto, fecha, null, saldos, reemplazados);
        return new CargaInicial(c, saldos, reemplazados, false);
    }

    private void saldo(Cuenta c, PosicionInversion p, BigDecimal monto, LocalDate fecha, String texto,
            List<OperacionInversion> saldos, List<SaldoAnterior> reemplazados) {
        Optional<OperacionInversion> anterior = p == null
                ? operaciones.findFirstByCuentaIdAndPosicionIsNullAndTipo(c.getId(), TipoOperacion.SALDO_INICIAL)
                : operaciones.findFirstByPosicionIdAndTipo(p.getId(), TipoOperacion.SALDO_INICIAL);
        if (anterior.isPresent()) {
            reemplazados.add(new SaldoAnterior(p == null ? null : p.getNombre(), anterior.get().getMonto(),
                    anterior.get().getFecha()));
            if (monto.signum() == 0) {
                operaciones.delete(anterior.get());
                return;
            }
            anterior.get().reemplazar(monto, fecha, texto);
            saldos.add(anterior.get());
            return;
        }
        if (monto.signum() > 0) {
            saldos.add(operaciones.save(new OperacionInversion(c, p, TipoOperacion.SALDO_INICIAL, monto, fecha, texto,
                    ahora())));
        }
    }

    // --- Aportes, retiros, compras y ventas ---

    /**
     * "Pasé 200.000 de Galicia a Cocos": un movimiento ENTRE_CUENTAS (la plata ya entró como ingreso al cobrarla).
     * Con una posición, además la compra (de la bolsa a la posición).
     *
     * @param desdeCuentaId de dónde salió; null si salió del efectivo
     */
    public Movimiento aportar(long cuentaId, Long posicionId, BigDecimal monto, Long desdeCuentaId,
            LocalDateTime fechaHora, Origen origen, String texto) {
        Cuenta c = deInversion(cuentaId);
        Cuenta desde = desdeCuentaId == null ? null : cuenta(desdeCuentaId);
        Movimiento m = movimientos.save(Movimiento.entreCuentas(monto, "Aporte a " + c.getNombre(), desde, c,
                desde == null ? efectivo() : null, fechaHora, origen, texto, ahora()));
        ajustar(c, null, monto, m.getId(), null, fechaHora.toLocalDate());
        if (posicionId != null) {
            operar(TipoOperacion.COMPRA, cuentaId, posicionId, monto, fechaHora.toLocalDate(), texto);
        }
        return m;
    }

    /**
     * "Saqué 150.000 de Cocos a Galicia": ENTRE_CUENTAS al revés. Desde una posición, primero la venta (de la
     * posición a la bolsa).
     *
     * @param haciaCuentaId a dónde fue; null si fue al efectivo
     */
    public Movimiento retirar(long cuentaId, Long posicionId, BigDecimal monto, Long haciaCuentaId,
            LocalDateTime fechaHora, Origen origen, String texto) {
        Cuenta c = deInversion(cuentaId);
        Cuenta hacia = haciaCuentaId == null ? null : cuenta(haciaCuentaId);
        if (posicionId != null) {
            operar(TipoOperacion.VENTA, cuentaId, posicionId, monto, fechaHora.toLocalDate(), texto);
        }
        Movimiento m = movimientos.save(Movimiento.entreCuentas(monto, "Retiro de " + c.getNombre(), c, hacia,
                hacia == null ? efectivo() : null, fechaHora, origen, texto, ahora()));
        ajustar(c, null, monto.negate(), m.getId(), null, fechaHora.toLocalDate());
        return m;
    }

    /** "Compré 100.000 de CEDEAR SPY" (de la bolsa a la posición) o "vendí 50.000 de SPY" (al revés). */
    public OperacionInversion operar(TipoOperacion tipo, long cuentaId, long posicionId, BigDecimal monto,
            LocalDate fecha, String texto) {
        if (tipo == TipoOperacion.SALDO_INICIAL) {
            throw new IllegalArgumentException("El saldo inicial se carga con cargarSaldoInicial");
        }
        Cuenta c = deInversion(cuentaId);
        PosicionInversion p = posicionPorId(posicionId);
        OperacionInversion o = operaciones.save(new OperacionInversion(c, p, tipo, monto, fecha, texto, ahora()));
        BigDecimal haciaLaPosicion = tipo == TipoOperacion.COMPRA ? monto : monto.negate();
        ajustar(c, null, haciaLaPosicion.negate(), null, o.getId(), fecha);
        ajustar(c, p, haciaLaPosicion, null, o.getId(), fecha);
        return o;
    }

    /** Si la tenencia ya tenía valor, el valor anterior más lo movido (nunca menos de cero). */
    private void ajustar(Cuenta c, PosicionInversion p, BigDecimal delta, Long movimientoId, Long operacionId,
            LocalDate fecha) {
        Long posicionId = p == null ? null : p.getId();
        PanelInversiones.Datos datos = panel.datos(c, List.of());
        CalculoInversion.Estado e = CalculoInversion.estado(new CalculoInversion.Clave(c.getId(), posicionId),
                datos.aportes(), datos.valuaciones(), LocalDate.MAX);
        if (e.sinValuar()) {
            return;
        }
        BigDecimal nuevo = e.valor().add(delta).max(BigDecimal.ZERO);
        LocalDate dia = e.ultimaValuacion().isAfter(fecha) ? e.ultimaValuacion() : fecha;
        valuaciones.registrar(c, p, dia, nuevo, OrigenValuacion.AJUSTE, movimientoId, operacionId, ahora());
    }

    /** Borra un aporte o un retiro (queda cancelado) con los ajustes que movió. */
    public Movimiento borrarMovimiento(long movimientoId) {
        Movimiento m = movimientos.findById(movimientoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el movimiento " + movimientoId));
        if (m.getTipo() != TipoMovimiento.ENTRE_CUENTAS || !m.isDeInversion()) {
            throw new IllegalArgumentException("Ese movimiento no es un aporte ni un retiro de una inversión");
        }
        m.anular();
        valuaciones.borrarAjustesDeMovimiento(movimientoId);
        return m;
    }

    /** Borra un saldo inicial, una compra o una venta, con los ajustes que movió. */
    public void borrarOperacion(long operacionId) {
        OperacionInversion o = operaciones.findById(operacionId)
                .orElseThrow(() -> new NoSuchElementException("No existe la operación " + operacionId));
        valuaciones.borrarAjustesDeOperacion(operacionId);
        operaciones.delete(o);
    }

    // --- Valuaciones ---

    /**
     * "Cocos vale 870.000" o "SPY está en 520.000". El valor de una cuenta con posiciones es el total: la bolsa
     * queda con lo que no explican las posiciones.
     *
     * @return las valuaciones creadas (para "Deshacer")
     */
    public List<ValuacionInversion> valuar(long cuentaId, Long posicionId, BigDecimal valor, LocalDate fecha) {
        if (valor == null || valor.signum() < 0) {
            throw new IllegalArgumentException("El valor no puede ser negativo");
        }
        Cuenta c = deInversion(cuentaId);
        if (posicionId != null) {
            PosicionInversion p = posicionPorId(posicionId);
            if (!p.getCuenta().getId().equals(c.getId())) {
                throw new IllegalArgumentException("La posición " + p.getNombre() + " no es de " + c.getNombre());
            }
            return List.of(valuaciones.registrar(c, p, fecha, valor, OrigenValuacion.MANUAL, null, null, ahora()));
        }
        PanelInversiones.EstadoCuenta estado = panel.cuenta(cuentaId);
        BigDecimal enPosiciones = estado.posiciones().stream().map(PanelInversiones.Tenencia::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (valor.compareTo(enPosiciones) < 0) {
            throw new IllegalArgumentException("Las posiciones de " + c.getNombre() + " ya valen "
                    + enPosiciones.toPlainString() + ": actualizá cada una");
        }
        return List.of(valuaciones.registrar(c, null, fecha, valor.subtract(enPosiciones), OrigenValuacion.MANUAL,
                null, null, ahora()));
    }

    /** "Igual que antes": cada tenencia con el valor que tenía, con la fecha de hoy. */
    public List<ValuacionInversion> igualQueAntes(long cuentaId, LocalDate fecha) {
        Cuenta c = deInversion(cuentaId);
        List<ValuacionInversion> nuevas = new ArrayList<>();
        for (PanelInversiones.Tenencia t : panel.cuenta(cuentaId).tenencias()) {
            PosicionInversion p = t.posicionId() == null ? null : posicionPorId(t.posicionId());
            nuevas.add(valuaciones.registrar(c, p, fecha, t.valor(), OrigenValuacion.IGUAL, null, null, ahora()));
        }
        return nuevas;
    }

    /** "Deshacer" una valuación, o borrarla desde el dashboard. */
    public void borrarValuaciones(List<Long> ids) {
        valuaciones.borrar(ids);
    }

    // --- Reservas ---

    /**
     * "Reservá 300.000 de Cocos para Bariloche": aparta parte del valor para una meta. Si ya había una reserva para
     * esa meta en la cuenta, la reemplaza. No se puede reservar más de lo que vale la cuenta.
     */
    public ReservaInversion reservar(long cuentaId, String meta, BigDecimal monto) {
        Cuenta c = deInversion(cuentaId);
        PanelInversiones.EstadoCuenta estado = panel.cuenta(cuentaId);
        Optional<ReservaInversion> anterior = reservas.findByCuentaIdAndLiberadaEnIsNullOrderById(cuentaId).stream()
                .filter(r -> NombresInversion.normalizar(r.getMeta()).equals(NombresInversion.normalizar(meta)))
                .findFirst();
        BigDecimal libre = estado.libre().add(anterior.map(ReservaInversion::getMonto).orElse(BigDecimal.ZERO));
        if (monto != null && monto.compareTo(libre) > 0) {
            throw new IllegalArgumentException(c.getNombre() + " tiene " + libre.toPlainString()
                    + " sin reservar: no alcanza para reservar " + monto.toPlainString());
        }
        ReservaInversion nueva = new ReservaInversion(c, meta, monto, ahora());
        anterior.ifPresent(r -> r.liberar(ahora()));
        return reservas.save(nueva);
    }

    public ReservaInversion liberarReserva(long reservaId) {
        ReservaInversion r = reservas.findById(reservaId)
                .orElseThrow(() -> new NoSuchElementException("No existe la reserva " + reservaId));
        r.liberar(ahora());
        return r;
    }

    /** "Liberá la reserva de Bariloche": las reservas activas para esa meta, en cualquier cuenta. */
    public List<ReservaInversion> liberarMeta(String meta) {
        String buscada = NombresInversion.normalizar(meta);
        List<ReservaInversion> liberadas = reservas.findByLiberadaEnIsNullOrderById().stream()
                .filter(r -> NombresInversion.normalizar(r.getMeta()).equals(buscada))
                .toList();
        liberadas.forEach(r -> r.liberar(ahora()));
        return liberadas;
    }

    // --- Recordatorio ---

    /** Una cuenta para la que toca preguntar cuánto vale hoy. */
    public record Recordatorio(long cuentaId, String cuenta, BigDecimal valor, LocalDate actualizado, long dias) {
    }

    /**
     * Las cuentas con recordatorio que hace sus días que no se actualizan ni se preguntan (desde la hora
     * configurada). Las vacías no se preguntan.
     */
    @Transactional(readOnly = true)
    public List<Recordatorio> recordatoriosPendientes() {
        LocalDateTime ahora = ahora();
        if (ahora.getHour() < properties.horaRecordatorio()) {
            return List.of();
        }
        List<Recordatorio> pendientes = new ArrayList<>();
        for (PanelInversiones.EstadoCuenta e : panel.panel().cuentas()) {
            Cuenta c = deInversion(e.id());
            Integer dias = c.getRecordatorioDias();
            if (dias == null || e.actualizado() == null || e.diasSinActualizar() < dias) {
                continue;
            }
            if (c.getRecordatorioEnviadoEn() != null
                    && ChronoUnit.DAYS.between(c.getRecordatorioEnviadoEn().toLocalDate(), ahora.toLocalDate()) < dias) {
                continue;
            }
            pendientes.add(new Recordatorio(e.id(), e.nombre(), e.valor(), e.actualizado(), e.diasSinActualizar()));
        }
        return pendientes;
    }

    /** Se anota recién cuando Telegram recibió la pregunta. */
    public void recordatorioEnviado(long cuentaId) {
        deInversion(cuentaId).recordatorioEnviado(ahora());
    }

    // --- Búsquedas ---

    @Transactional(readOnly = true)
    public List<Cuenta> cuentas() {
        return cuentas.findByActivaTrueAndTipoOrderById(TipoCuenta.INVERSION);
    }

    @Transactional(readOnly = true)
    public List<PosicionInversion> posiciones() {
        return cuentas().stream()
                .flatMap(c -> posiciones.findByCuentaIdOrderById(c.getId()).stream())
                .filter(PosicionInversion::isActiva)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Cuenta> porNombre(String nombre) {
        String buscado = nombre == null ? "" : NombresInversion.normalizar(nombre);
        return cuentas().stream().filter(c -> NombresInversion.normalizar(c.getNombre()).equals(buscado)).findFirst();
    }

    private Cuenta deInversion(long id) {
        Cuenta c = cuenta(id);
        if (!c.isInversion()) {
            throw new IllegalArgumentException(c.getNombre() + " no es una cuenta de inversión");
        }
        return c;
    }

    private Cuenta cuenta(long id) {
        return cuentas.findById(id).orElseThrow(() -> new NoSuchElementException("No existe la cuenta " + id));
    }

    private PosicionInversion posicionPorId(long id) {
        return posiciones.findById(id).orElseThrow(() -> new NoSuchElementException("No existe la posición " + id));
    }

    private MedioPago efectivo() {
        return medios.findByActivoTrueOrderById().stream().filter(MedioPago::isEfectivo).findFirst().orElse(null);
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

}
