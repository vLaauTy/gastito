package ar.gastito.sueldo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalculoSueldoRepository extends JpaRepository<CalculoSueldo, Long> {

    Optional<CalculoSueldo> findByPeriodo(LocalDate periodo);

    List<CalculoSueldo> findByPeriodoBetweenOrderByPeriodo(LocalDate desde, LocalDate hasta);
}
