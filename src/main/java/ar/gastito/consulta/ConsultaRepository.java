package ar.gastito.consulta;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/** Lo que leen las herramientas: gastos confirmados y los productos de sus tickets. Rangos [desde, hasta). */
interface ConsultaRepository extends Repository<Movimiento, Long> {

    @EntityGraph(attributePaths = {"categoria", "medioPago", "cuenta", "tarjeta"})
    @Query("""
            select m from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            order by m.fechaHora desc, m.id desc""")
    List<Movimiento> gastos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Los productos (no los descuentos) de los tickets de gastos confirmados. */
    @Query("""
            select i from MovimientoItem i join fetch i.movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
              and i.subtotal > 0""")
    List<MovimientoItem> productos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
