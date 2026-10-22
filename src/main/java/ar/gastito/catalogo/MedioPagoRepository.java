package ar.gastito.catalogo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MedioPagoRepository extends JpaRepository<MedioPago, Long> {

    List<MedioPago> findByActivoTrueOrderById();

    /** El medio de pago que significa "en efectivo". */
    Optional<MedioPago> findFirstByEfectivoTrueOrderById();
}
