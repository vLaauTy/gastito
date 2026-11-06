package ar.gastito.inversion;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ValuacionInversionRepository extends JpaRepository<ValuacionInversion, Long> {

    List<ValuacionInversion> findByCuentaIdOrderByFechaAscCreadaEnAscIdAsc(Long cuentaId);

    List<ValuacionInversion> findByMovimientoId(Long movimientoId);

    List<ValuacionInversion> findByOperacionId(Long operacionId);
}
