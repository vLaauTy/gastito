package ar.gastito.sueldo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalaSueldoRepository extends JpaRepository<EscalaSueldo, Long> {

    List<EscalaSueldo> findAllByOrderByVigenteDesdeDesc();

    /** La escala que rige en esa fecha: la de "desde" más reciente que no la pase. */
    Optional<EscalaSueldo> findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(LocalDate fecha);

    Optional<EscalaSueldo> findByVigenteDesde(LocalDate desde);
}
