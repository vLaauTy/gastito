package ar.gastito.recibo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MapeoConceptoReciboRepository extends JpaRepository<MapeoConceptoRecibo, Long> {

    List<MapeoConceptoRecibo> findByClaveIn(Collection<String> claves);
}
