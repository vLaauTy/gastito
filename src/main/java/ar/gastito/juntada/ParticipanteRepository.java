package ar.gastito.juntada;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipanteRepository extends JpaRepository<Participante, Long> {

    /** De quién era la parte que se convirtió en este gasto al darla por perdida. */
    Optional<Participante> findByGastoPerdidoId(Long gastoId);
}
