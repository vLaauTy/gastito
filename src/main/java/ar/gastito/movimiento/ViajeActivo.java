package ar.gastito.movimiento;

import java.time.LocalDate;
import java.util.Optional;

/**
 * El modo viaje: si hay un viaje que incluye un día, lo que se carga con esa fecha se asocia solo. Lo implementa el
 * paquete de los viajes (sin viajes, nada se asocia).
 */
public interface ViajeActivo {

    Optional<Long> viajeDe(LocalDate dia);
}
