package ar.gastito.movimiento;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TarjetaRepository extends JpaRepository<Tarjeta, Long> {

    /** Por ahora hay una sola tarjeta de crédito (la MasterCard Gold de Galicia). */
    Optional<Tarjeta> findFirstByActivaTrueOrderById();
}
