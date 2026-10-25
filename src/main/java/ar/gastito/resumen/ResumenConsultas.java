package ar.gastito.resumen;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoItem;

/** Lo que el resumen lee de la base: gastos confirmados y los productos de sus tickets. Rangos [desde, hasta). */
interface ResumenConsultas extends Repository<Movimiento, Long> {

    @EntityGraph(attributePaths = {"categoria", "medioPago", "cuenta", "tarjeta"})
    @Query("""
            select m from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            order by m.fechaHora, m.id""")
    List<Movimiento> gastos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Si hay gastos confirmados anteriores a {@code antes} en alguna de esas categorías (nombres en minúscula). */
    @Query("""
            select count(m) > 0 from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora < :antes
              and lower(m.categoria.nombre) in :categorias""")
    boolean hayGastosAntes(@Param("categorias") Collection<String> categorias, @Param("antes") LocalDateTime antes);

    /** Los productos (no los descuentos) de los tickets de gastos confirmados. */
    @Query("""
            select i from MovimientoItem i join i.movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
              and i.subtotal > 0""")
    List<MovimientoItem> productos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
