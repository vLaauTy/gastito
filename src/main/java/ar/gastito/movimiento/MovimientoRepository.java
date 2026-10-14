package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los totales solo suman gastos confirmados (tipo GASTO, estado CONFIRMADO).
 * Los rangos de fecha son [desde, hasta).
 */
public interface MovimientoRepository extends JpaRepository<Movimiento, Long> {

    @EntityGraph(attributePaths = {"categoria", "medioPago", "cuenta", "tarjeta"})
    Optional<Movimiento> findConDetalleById(Long id);

    @Query("""
            select coalesce(sum(m.monto), 0) from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.categoria.id = :categoriaId
              and m.fechaHora >= :desde and m.fechaHora < :hasta""")
    BigDecimal totalGastosDeCategoria(@Param("categoriaId") Long categoriaId,
            @Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query("""
            select coalesce(sum(m.monto), 0) from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta""")
    BigDecimal totalGastos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query("""
            select new ar.gastito.movimiento.TotalPorCategoria(c.nombre, sum(m.monto))
            from Movimiento m join m.categoria c
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            group by c.nombre
            order by sum(m.monto) desc""")
    List<TotalPorCategoria> totalesPorCategoria(@Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    @EntityGraph(attributePaths = {"categoria", "medioPago"})
    @Query("""
            select m from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            order by m.fechaHora""")
    List<Movimiento> gastosConfirmados(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
