package ar.gastito.movimiento;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MovimientoUnionRepository extends JpaRepository<MovimientoUnion, Long> {

    List<MovimientoUnion> findByMovimientoId(Long movimientoId);
}
