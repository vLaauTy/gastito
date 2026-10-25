package ar.gastito.resumen;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface EnvioResumenRepository extends JpaRepository<EnvioResumen, Long> {

    /** El último período registrado de un tipo, sea cual sea su estado. */
    Optional<EnvioResumen> findFirstByTipoOrderByDesdeDesc(TipoResumen tipo);

    Optional<EnvioResumen> findByTipoAndDesde(TipoResumen tipo, LocalDate desde);

    List<EnvioResumen> findByTipoAndEstadoAndDesdeBetweenOrderByDesde(TipoResumen tipo, EnvioResumen.Estado estado,
            LocalDate desde, LocalDate hasta);
}
