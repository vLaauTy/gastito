package ar.gastito.recibo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Movimiento;

public interface ReciboRepository extends JpaRepository<ReciboSueldo, Long> {

    Optional<ReciboSueldo> findByMovimientoId(Long movimientoId);

    /** Los recibos cuyo ingreso está guardado y con período entre [desde, hasta], del más viejo al más nuevo. */
    @Query("""
            select r from ReciboSueldo r
            where r.periodo >= :desde and r.periodo <= :hasta
              and exists (select 1 from Movimiento m where m.id = r.movimientoId
                          and m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO)
            order by r.periodo, r.id""")
    List<ReciboSueldo> guardadosEntre(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    /** Los ingresos guardados de una clase en [desde, hasta), menos el indicado. */
    @Query("""
            select m from Movimiento m
            where m.tipo = ar.gastito.movimiento.TipoMovimiento.INGRESO
              and m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.claseIngreso = :clase
              and m.fechaHora >= :desde and m.fechaHora < :hasta
              and m.id <> :excepto
            order by m.fechaHora desc, m.id desc""")
    List<Movimiento> ingresosGuardados(@Param("clase") ClaseIngreso clase, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta, @Param("excepto") Long excepto);
}
