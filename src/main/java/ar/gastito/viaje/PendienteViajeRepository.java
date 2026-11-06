package ar.gastito.viaje;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendienteViajeRepository extends JpaRepository<PendienteViaje, Long> {

    List<PendienteViaje> findByMovimientoId(Long movimientoId);
}
