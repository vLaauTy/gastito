package ar.gastito.resumen;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumenRepository extends JpaRepository<Resumen, Long> {

    /** El último que se armó (para el dashboard). */
    Optional<Resumen> findFirstByOrderByCreadoEnDescIdDesc();
}
