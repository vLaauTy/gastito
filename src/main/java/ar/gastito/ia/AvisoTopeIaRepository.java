package ar.gastito.ia;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AvisoTopeIaRepository extends JpaRepository<AvisoTopeIa, Long> {

    boolean existsByMesAndUmbral(LocalDate mes, int umbral);
}
