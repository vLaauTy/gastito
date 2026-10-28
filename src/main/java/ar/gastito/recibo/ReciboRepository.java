package ar.gastito.recibo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.gastito.movimiento.Movimiento;

public interface ReciboRepository extends JpaRepository<ReciboSueldo, Long> {

    List<ReciboSueldo> findByPeriodoAndTipo(LocalDate periodo, TipoRecibo tipo);

    /** El recibo más reciente del mismo tipo con período anterior al indicado. */
    Optional<ReciboSueldo> findFirstByPeriodoLessThanAndTipoOrderByPeriodoDescIdDesc(LocalDate periodo,
            TipoRecibo tipo);

    /** Los recibos con período entre [desde, hasta], del más viejo al más nuevo. */
    @Query("""
            select r from ReciboSueldo r
            where r.periodo >= :desde and r.periodo <= :hasta
            order by r.periodo, r.id""")
    List<ReciboSueldo> entre(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    /** El ingreso guardado (no cancelado ni pendiente) con ese id. */
    @Query("""
            select m from Movimiento m
            where m.id = :id
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.INGRESO
              and m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO""")
    Optional<Movimiento> ingresoGuardado(@Param("id") Long id);
}
