package ar.gastito.viaje;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ViajeRepository extends JpaRepository<Viaje, Long> {

    @EntityGraph(attributePaths = "pendientes")
    Optional<Viaje> findConPendientesById(Long id);

    /** Del más reciente al más viejo. */
    List<Viaje> findAllByOrderByDesdeDesc();

    /** Los viajes que incluyen ese día (si se superponen, el que empezó último primero). */
    @Query("select v from Viaje v where v.desde <= :dia and v.hasta >= :dia order by v.desde desc, v.id desc")
    List<Viaje> queIncluyen(@Param("dia") LocalDate dia);
}
