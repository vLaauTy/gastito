package ar.gastito.juntada;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Una juntada lista para mostrar (en el bot o en el dashboard), armada dentro de la transacción.
 *
 * @param miParte   lo que cuenta como gasto mío (el monto del movimiento)
 * @param aCobrar   lo que todavía me deben
 * @param billetera desde dónde pagué el total ("Mercado Pago", "MasterCard Gold"), o null si no se sabe
 * @param guardada  el gasto ya está guardado (si no, es la tarjeta pendiente)
 */
public record VistaJuntada(Long movimientoId, Long juntadaId, String descripcion, String categoria,
        LocalDateTime fechaHora, BigDecimal totalPagado, BigDecimal miParte, BigDecimal aCobrar, int personas,
        String billetera, boolean guardada, List<Persona> participantes) {

    /**
     * @param monto    su parte
     * @param pagado   lo que ya me pagó (puede ser menos que su parte si quedó debiendo, o si se dio por saldado)
     * @param restante lo que todavía me debe (cero si ya no está pendiente)
     */
    public record Persona(Long id, String nombre, BigDecimal monto, BigDecimal pagado, BigDecimal restante,
            EstadoParticipante estado, boolean montoAMano) {
    }

    public List<Persona> pendientes() {
        return participantes.stream().filter(p -> p.estado() == EstadoParticipante.PENDIENTE).toList();
    }

    /** Todos ponen lo mismo (salvo el redondeo de mi parte). */
    public boolean partesIguales() {
        return participantes.stream().map(p -> p.monto().stripTrailingZeros()).distinct().count() <= 1;
    }
}
