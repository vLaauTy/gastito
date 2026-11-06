package ar.gastito.inversion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * De dónde salen los valores de las inversiones. Hoy los carga el usuario a mano ({@link ValuacionesManuales}); más
 * adelante puede haber otra fuente (una app de inversiones propia) que se sume sin tocar el cálculo del rendimiento.
 */
public interface FuenteDeValuaciones {

    /**
     * Cuánto valía la bolsa de una cuenta (posicionId null) o una posición en una fecha.
     *
     * @param id el id en la fuente (para borrarla, si la fuente lo permite)
     */
    record Valuacion(Long id, long cuentaId, Long posicionId, LocalDate fecha, BigDecimal valor,
            OrigenValuacion origen, LocalDateTime creadaEn) {
    }

    /** Todas las valuaciones de una cuenta (de la bolsa y de sus posiciones), de la más vieja a la más nueva. */
    List<Valuacion> deLaCuenta(long cuentaId);
}
