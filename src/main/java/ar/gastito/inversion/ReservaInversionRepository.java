package ar.gastito.inversion;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservaInversionRepository extends JpaRepository<ReservaInversion, Long> {

    List<ReservaInversion> findByLiberadaEnIsNullOrderById();

    List<ReservaInversion> findByCuentaIdAndLiberadaEnIsNullOrderById(Long cuentaId);
}
