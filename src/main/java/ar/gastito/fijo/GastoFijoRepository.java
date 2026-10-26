package ar.gastito.fijo;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GastoFijoRepository extends JpaRepository<GastoFijo, Long> {

    @EntityGraph(attributePaths = {"categoria", "medioPago", "cuenta", "tarjeta"})
    List<GastoFijo> findByActivoTrueOrderByDiaDelMesAscNombreAsc();
}
