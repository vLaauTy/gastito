package ar.gastito.sueldo.escala;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AliasEscalaRepository extends JpaRepository<AliasEscala, Long> {

    List<AliasEscala> findAllByOrderByIdAsc();

    Optional<AliasEscala> findByClave(String clave);
}
