package ar.gastito.sueldo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptoSueldoRepository extends JpaRepository<ConceptoSueldo, Long> {

    List<ConceptoSueldo> findAllByOrderByOrdenAscIdAsc();
}
