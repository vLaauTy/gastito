package ar.gastito.movimiento;

import java.time.LocalDate;
import java.util.Optional;

/**
 * El modo viaje: si hay un viaje que incluye un día, lo que se carga con esa fecha se asocia solo. Lo implementa el
 * paquete de los viajes (sin viajes, nada se asocia).
 */
public interface ViajeActivo {

    Optional<Long> viajeDe(LocalDate dia);

    /** El viaje que nombra el texto ("pagué 293.000 del pasaje a Bariloche"), aunque la fecha no sea del viaje. */
    default Optional<Long> viajeNombradoEn(String texto) {
        return Optional.empty();
    }
}
