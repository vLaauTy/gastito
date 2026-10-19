package ar.gastito.catalogo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MedioPagoRepository extends JpaRepository<MedioPago, Long> {

    List<MedioPago> findByActivoTrueOrderById();
}
