package ar.gastito.fijo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordatorioFijoRepository extends JpaRepository<RecordatorioFijo, Long> {

    Optional<RecordatorioFijo> findTopByOrderByIdDesc();
}
