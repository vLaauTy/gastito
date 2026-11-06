package ar.gastito.viaje;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import ar.gastito.juntada.VistaJuntada;
import ar.gastito.prestamo.VistaPrestamo;

/**
 * Todo lo de un viaje, calculado en Java para el bot y el dashboard (el JavaScript no calcula nada).
 *
 * @param pagado            lo que ya gasté yo en el viaje (mis partes)
 * @param falta             lo que falta pagar con monto aproximado
 * @param sinMonto          cuántos pendientes no tienen monto (no están en falta)
 * @param totalEstimado     pagado + falta
 * @param porcentajePresupuesto el total estimado contra el presupuesto (null sin presupuesto)
 * @param meDeben           lo que me deben por el viaje (préstamos y juntadas)
 * @param porLosDemas       lo que puse por los demás (las partes de los demás en las juntadas y lo prestado)
 * @param estado            PROXIMO, EN_CURSO o TERMINADO
 * @param ahorro            cuánto separar por mes hasta el viaje (null si ya empezó o no falta nada con monto)
 */
public record PanelViaje(Long id, String nombre, String emoji, LocalDate desde, LocalDate hasta, long dias,
        Integer personas, BigDecimal presupuesto, String notas, String estado, BigDecimal pagado, BigDecimal falta,
        int sinMonto, BigDecimal totalEstimado, BigDecimal porcentajePresupuesto, BigDecimal meDeben,
        BigDecimal porLosDemas, List<Pendiente> pendientes, List<PorCategoria> porCategoria, List<PorDia> porDia,
        List<VistaPrestamo> prestamos, List<VistaJuntada> juntadas, AhorroViaje ahorro) {

    public static final String PROXIMO = "PROXIMO";
    public static final String EN_CURSO = "EN_CURSO";
    public static final String TERMINADO = "TERMINADO";

    /** @param movimientoId el gasto con que se pagó (pagado o todavía para confirmar), o null */
    public record Pendiente(Long id, String descripcion, BigDecimal montoAproximado, Long categoriaId,
            boolean pagado, Long movimientoId, BigDecimal montoPagado) {
    }

    public record PorCategoria(Long categoriaId, String categoria, String emoji, BigDecimal total,
            BigDecimal porcentaje) {
    }

    public record PorDia(LocalDate dia, BigDecimal total) {
    }

    public String nombreConEmoji() {
        return emoji == null ? nombre : emoji + " " + nombre;
    }

    public List<Pendiente> sinPagar() {
        return pendientes.stream().filter(p -> !p.pagado()).toList();
    }
}
