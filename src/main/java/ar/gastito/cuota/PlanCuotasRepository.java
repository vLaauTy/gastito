package ar.gastito.cuota;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanCuotasRepository extends JpaRepository<PlanCuotas, Long> {

    @EntityGraph(attributePaths = {"categoria", "tarjeta"})
    List<PlanCuotas> findByEstadoOrderByIdDesc(EstadoPlanCuotas estado);
}
