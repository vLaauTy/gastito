package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los totales solo suman gastos confirmados (tipo GASTO, estado CONFIRMADO).
 * Los rangos de fecha son [desde, hasta).
 */
public interface MovimientoRepository extends JpaRepository<Movimiento, Long> {

    @EntityGraph(attributePaths = {"categoria", "medioPago", "cuenta", "tarjeta", "candidatas"})
    Optional<Movimiento> findConDetalleById(Long id);

    /** Últimos gastos confirmados, del más nuevo al más viejo (ejemplos para la IA). */
    @EntityGraph(attributePaths = {"categoria"})
    @Query("""
            select m from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.descripcion is not null
            order by m.creadoEn desc, m.id desc""")
    List<Movimiento> ultimosConfirmados(Pageable pagina);

    /** Cada descripción confirmada con la categoría que le dio el usuario (sin repetidos). */
    @Query("""
            select distinct new ar.gastito.movimiento.DescripcionConfirmada(m.descripcion, m.categoria.id)
            from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.descripcion is not null and m.categoria is not null""")
    List<DescripcionConfirmada> descripcionesConfirmadas();

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
            select new ar.gastito.movimiento.TotalPorCategoria(c.nombre, c.emoji, sum(m.monto))
            from Movimiento m join m.categoria c
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            group by c.nombre, c.emoji
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

    // --- Ingresos ---

    @EntityGraph(attributePaths = {"cuenta", "medioPago"})
    @Query("""
            select m from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.INGRESO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            order by m.fechaHora, m.id""")
    List<Movimiento> ingresosConfirmados(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query("""
            select coalesce(sum(m.monto), 0) from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.INGRESO
              and m.fechaHora >= :desde and m.fechaHora < :hasta""")
    BigDecimal totalIngresos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Total de ingresos confirmados por año y mes, solo de los meses que tienen alguno. */
    @Query("""
            select new ar.gastito.movimiento.TotalPorMes(year(m.fechaHora), month(m.fechaHora), sum(m.monto))
            from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.INGRESO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            group by year(m.fechaHora), month(m.fechaHora)""")
    List<TotalPorMes> ingresosPorMes(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Los últimos ingresos guardados de una clase, del más nuevo al más viejo. */
    @EntityGraph(attributePaths = {"cuenta", "medioPago"})
    @Query("""
            select m from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.INGRESO
              and m.claseIngreso = :clase
            order by m.fechaHora desc, m.id desc""")
    List<Movimiento> ultimosIngresos(@Param("clase") ClaseIngreso clase, Pageable pagina);
}
