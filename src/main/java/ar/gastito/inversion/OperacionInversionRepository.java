package ar.gastito.inversion;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OperacionInversionRepository extends JpaRepository<OperacionInversion, Long> {

    List<OperacionInversion> findByCuentaIdOrderByFechaAscIdAsc(Long cuentaId);

    Optional<OperacionInversion> findFirstByCuentaIdAndPosicionIsNullAndTipo(Long cuentaId, TipoOperacion tipo);

    Optional<OperacionInversion> findFirstByPosicionIdAndTipo(Long posicionId, TipoOperacion tipo);
}
