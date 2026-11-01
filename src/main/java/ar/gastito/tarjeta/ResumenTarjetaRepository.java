package ar.gastito.tarjeta;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumenTarjetaRepository extends JpaRepository<ResumenTarjeta, Long> {

    List<ResumenTarjeta> findByTarjetaIdOrderByPeriodo(Long tarjetaId);
}
