package ar.gastito.juntada;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JuntadaRepository extends JpaRepository<Juntada, Long> {

    @EntityGraph(attributePaths = {"movimiento", "participantes"})
    Optional<Juntada> findByMovimientoId(Long movimientoId);

    /** Las juntadas guardadas en las que alguien todavía me debe, de la más nueva a la más vieja. */
    @Query("""
            select distinct j from Juntada j join fetch j.movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and exists (select p from Participante p where p.juntada = j
                          and p.estado = ar.gastito.juntada.EstadoParticipante.PENDIENTE)
            order by m.fechaHora desc""")
    List<Juntada> conDeudasPendientes();
}
