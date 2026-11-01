package ar.gastito.tarjeta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un resumen de la tarjeta listo para mostrar (bot y dashboard), con lo cargado en Gastito ya sumado en Java.
 *
 * @param id         null si es un resumen futuro que todavía no está guardado (solo tiene cuotas comprometidas)
 * @param desde      el primer día del período (el siguiente al cierre anterior)
 * @param estimadas  las fechas son una estimación (≈)
 * @param compras    las compras con la tarjeta del período (de una juntada, el total que se pagó)
 * @param cuotas     las cuotas que caen en este resumen
 * @param cargado    compras + cuotas
 * @param totalReal  lo que dice el resumen del banco, o null si no se cargó
 * @param diferencia totalReal − cargado (positiva: falta cargar), o null sin total real
 */
public record VistaResumenTarjeta(Long id, YearMonth periodo, LocalDate desde, LocalDate cierre, LocalDate vencimiento,
        boolean estimadas, EstadoResumenTarjeta estado, BigDecimal compras, BigDecimal cuotas, BigDecimal cargado,
        BigDecimal totalReal, BigDecimal diferencia, String tarjeta, String cuentaDebito, List<Linea> detalle) {

    /**
     * Una compra o una cuota del resumen.
     *
     * @param cuota   el número de cuota, o null si es una compra en un pago
     * @param juntada si fue una juntada: el monto es el total que se pagó con la tarjeta, no mi parte
     */
    public record Linea(Long movimientoId, LocalDateTime fecha, String descripcion, String categoria, String emoji,
            BigDecimal monto, Integer cuota, Integer cuotas, boolean juntada) {
    }

    /** Lo que se va a debitar: el total del resumen si se cargó; si no, lo cargado en Gastito. */
    @JsonProperty
    public BigDecimal aDebitar() {
        return totalReal != null ? totalReal : cargado;
    }

    @JsonProperty
    public int cantidadCompras() {
        return (int) detalle.stream().filter(l -> l.cuota() == null).count();
    }

    @JsonProperty
    public int cantidadCuotas() {
        return (int) detalle.stream().filter(l -> l.cuota() != null).count();
    }
}
