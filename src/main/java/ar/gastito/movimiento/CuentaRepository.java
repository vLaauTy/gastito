package ar.gastito.movimiento;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CuentaRepository extends JpaRepository<Cuenta, Long> {

    List<Cuenta> findByActivaTrueOrderById();
}
