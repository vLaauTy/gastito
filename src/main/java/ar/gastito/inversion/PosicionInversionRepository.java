package ar.gastito.inversion;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PosicionInversionRepository extends JpaRepository<PosicionInversion, Long> {

    List<PosicionInversion> findByCuentaIdOrderById(Long cuentaId);
}
