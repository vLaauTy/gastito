package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.catalogo.Cuenta;
import ar.gastito.inversion.InversionService;
import ar.gastito.inversion.PanelInversiones;
import ar.gastito.inversion.TipoOperacion;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.Periodo;

/**
 * La pestaña Inversiones: el panel (lo que vale, lo puesto, el rendimiento, las posiciones, las reservas, el historial
 * y la serie del gráfico, todo calculado en Java) y lo que se carga desde ahí. También la línea "Ahorros e
 * inversiones" del Resumen. Nada de esto es un gasto ni un ingreso.
 */
@RestController
@RequestMapping("/api/inversiones")
public class InversionesController {

    private final InversionService inversiones;
    private final PanelInversiones panel;
    private final DashboardService dashboard;
    private final Clock clock;

    public InversionesController(InversionService inversiones, PanelInversiones panel, DashboardService dashboard,
            Clock clock) {
        this.inversiones = inversiones;
        this.panel = panel;
        this.dashboard = dashboard;
        this.clock = clock;
    }

    @GetMapping
    public PanelInversiones.Panel todo() {
        return panel.panel();
    }

    /**
     * La línea del Resumen, solo si hay inversiones.
     *
     * @param movidoEnElMes      lo que se pasó a las inversiones menos lo que se sacó en el mes: salió del
     *                           disponible, así que no se suma dos veces
     * @param totalConDisponible lo invertido más lo disponible del mes (sin lo movido); null si el mes no tiene
     *                           ingresos o no es el actual
     */
    public record Ahorros(boolean hay, BigDecimal invertido, BigDecimal movidoEnElMes, BigDecimal totalConDisponible) {
    }

    @GetMapping("/resumen")
    public Ahorros resumen(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        if (!panel.hayInversiones()) {
            return new Ahorros(false, null, null, null);
        }
        YearMonth elMes = mes == null ? YearMonth.now(clock) : mes;
        BigDecimal invertido = panel.totalInvertido();
        Periodo periodo = Periodo.delMes(elMes);
        BigDecimal movido = panel.aportesNetos(periodo.desde(), periodo.hasta());
        DashboardService.Resumen r = dashboard.resumen(elMes);
        BigDecimal total = r.mesActual() && r.disponible() != null
                ? r.disponible().monto().subtract(movido).add(invertido).setScale(2, RoundingMode.HALF_UP)
                : null;
        return new Ahorros(true, invertido, movido, total);
    }

    // --- Cuentas y posiciones ---

    /** @param recordatorioDias cada cuántos días preguntar cuánto vale (null o 0: no preguntar) */
    public record DatosCuenta(String nombre, String alias, Integer recordatorioDias) {
    }

    public record CuentaCreada(long id, String nombre) {

        static CuentaCreada de(Cuenta c) {
            return new CuentaCreada(c.getId(), c.getNombre());
        }
    }

    @PostMapping(path = "/cuentas", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CuentaCreada crearCuenta(@RequestBody DatosCuenta datos) {
        Cuenta c = inversiones.crearCuenta(datos.nombre(), datos.alias());
        if (datos.recordatorioDias() != null) {
            inversiones.cambiarRecordatorio(c.getId(), datos.recordatorioDias());
        }
        return CuentaCreada.de(c);
    }

    @PutMapping(path = "/cuentas/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CuentaCreada editarCuenta(@PathVariable long id, @RequestBody DatosCuenta datos) {
        return CuentaCreada.de(inversiones.editarCuenta(id, datos.nombre(), datos.alias(), datos.recordatorioDias()));
    }

    @PostMapping("/cuentas/{id}/baja")
    public void bajaCuenta(@PathVariable long id) {
        inversiones.darDeBaja(id);
    }

    public record DatosPosicion(String nombre) {
    }

    @PostMapping(path = "/cuentas/{id}/posiciones", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CuentaCreada crearPosicion(@PathVariable long id, @RequestBody DatosPosicion datos) {
        var p = inversiones.posicion(id, datos.nombre());
        return new CuentaCreada(p.getId(), p.getNombre());
    }

    @PostMapping("/posiciones/{id}/baja")
    public void bajaPosicion(@PathVariable long id) {
        inversiones.darDeBajaPosicion(id);
    }

    // --- Saldo inicial, valuaciones, aportes y retiros ---

    /** @param posicionId null para la bolsa de la cuenta (o la cuenta entera si no tiene posiciones) */
    public record DatosMonto(long cuentaId, Long posicionId, BigDecimal monto, LocalDate fecha) {
    }

    @PostMapping(path = "/saldo-inicial", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void saldoInicial(@RequestBody DatosMonto d) {
        inversiones.cargarSaldo(d.cuentaId(), d.posicionId(), d.monto(), fechaOHoy(d.fecha()));
    }

    /** "Actualizar valor": de una posición, o el total de la cuenta si posicionId es null. */
    @PostMapping(path = "/valuaciones", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void valuar(@RequestBody DatosMonto d) {
        inversiones.valuar(d.cuentaId(), d.posicionId(), d.monto(), fechaOHoy(d.fecha()));
    }

    @DeleteMapping("/valuaciones/{id}")
    public void borrarValuacion(@PathVariable long id) {
        inversiones.borrarValuaciones(List.of(id));
    }

    public enum TipoCarga {
        APORTE, RETIRO, COMPRA, VENTA
    }

    /**
     * @param otraCuentaId en un aporte, de dónde salió; en un retiro, a dónde fue (null: efectivo)
     * @param posicionId   la posición; obligatoria en una compra o una venta
     */
    public record DatosOperacion(TipoCarga tipo, long cuentaId, Long posicionId, Long otraCuentaId, BigDecimal monto,
            LocalDate fecha) {
    }

    @PostMapping(path = "/operaciones", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void operar(@RequestBody DatosOperacion d) {
        if (d.tipo() == null) {
            throw new IllegalArgumentException("Elegí qué querés cargar");
        }
        LocalDate fecha = fechaOHoy(d.fecha());
        switch (d.tipo()) {
            case APORTE -> inversiones.aportar(d.cuentaId(), d.posicionId(), d.monto(), d.otraCuentaId(),
                    fecha.atTime(LocalTime.NOON), Origen.DASHBOARD, null);
            case RETIRO -> inversiones.retirar(d.cuentaId(), d.posicionId(), d.monto(), d.otraCuentaId(),
                    fecha.atTime(LocalTime.NOON), Origen.DASHBOARD, null);
            case COMPRA, VENTA -> {
                if (d.posicionId() == null) {
                    throw new IllegalArgumentException("Elegí la posición");
                }
                inversiones.operar(d.tipo() == TipoCarga.COMPRA ? TipoOperacion.COMPRA : TipoOperacion.VENTA,
                        d.cuentaId(), d.posicionId(), d.monto(), fecha, null);
            }
        }
    }

    /** Un aporte o un retiro (queda cancelado, con los ajustes que movió). */
    @DeleteMapping("/movimientos/{id}")
    public void borrarMovimiento(@PathVariable long id) {
        inversiones.borrarMovimiento(id);
    }

    /** Un saldo inicial, una compra o una venta. */
    @DeleteMapping("/operaciones/{id}")
    public void borrarOperacion(@PathVariable long id) {
        inversiones.borrarOperacion(id);
    }

    // --- Reservas ---

    public record DatosReserva(long cuentaId, String meta, BigDecimal monto) {
    }

    @PostMapping(path = "/reservas", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void reservar(@RequestBody DatosReserva d) {
        inversiones.reservar(d.cuentaId(), d.meta(), d.monto());
    }

    @PostMapping("/reservas/{id}/liberar")
    public void liberar(@PathVariable long id) {
        inversiones.liberarReserva(id);
    }

    private LocalDate fechaOHoy(LocalDate fecha) {
        LocalDate hoy = LocalDate.now(clock);
        if (fecha != null && fecha.isAfter(hoy)) {
            throw new IllegalArgumentException("La fecha no puede ser futura");
        }
        return fecha == null ? hoy : fecha;
    }
}
