package ar.gastito.dolar;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CotizacionDolarRepository extends JpaRepository<CotizacionDolar, Long> {

    /** La de referencia: la última que se cargó. */
    Optional<CotizacionDolar> findFirstByOrderByCargadaEnDescIdDesc();
}
