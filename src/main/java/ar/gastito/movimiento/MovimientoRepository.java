package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los totales solo suman gastos confirmados (tipo GASTO, estado CONFIRMADO).
 * Los rangos de fecha son [desde, hasta).
 */
public interface MovimientoRepository extends JpaRepository<Movimiento, Long> {

    // Sin "items": junto con las candidatas, el JOIN repetiría cada producto una vez por candidata.
    @EntityGraph(attributePaths = {"categoria", "medioPago", "cuenta", "tarjeta", "candidatas"})
    Optional<Movimiento> findConDetalleById(Long id);

    /**
     * Los gastos contra los que se busca una compra repetida, como huellas (id, fecha, monto, categoría, comercio y 1
     * si es el cobro pendiente de un fijo):
     * los confirmados entre desde y hasta que ya ocurrieron (las cuotas futuras no), y los cobros pendientes de
     * gastos fijos del mes. El monto de una juntada es el total pagado, no mi parte.
     */
    @Query(nativeQuery = true, value = """
            SELECT m.id, m.fecha_hora, COALESCE(j.total_pagado, m.monto), m.categoria_id, m.comercio,
                   CASE WHEN m.estado = 'PENDIENTE' THEN 1 ELSE 0 END
            FROM movimiento m LEFT JOIN juntada j ON j.movimiento_id = m.id
            WHERE m.tipo = 'GASTO' AND m.id <> :excepto AND m.monto IS NOT NULL
              AND ((m.estado = 'CONFIRMADO' AND m.fecha_hora BETWEEN :desde AND :hasta AND m.fecha_hora <= :ahora)
                OR (m.estado = 'PENDIENTE' AND m.gasto_fijo_id IS NOT NULL AND m.fijo_periodo = :periodo))""")
    List<Object[]> huellasCandidatas(@Param("excepto") Long excepto, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta, @Param("ahora") LocalDateTime ahora,
            @Param("periodo") LocalDate periodo);

    /** El total que pagué en la juntada de este movimiento, o null si no es una juntada. */
    @Query(nativeQuery = true, value = "SELECT total_pagado FROM juntada WHERE movimiento_id = :id")
    BigDecimal totalDeJuntada(@Param("id") Long id);

    /** Al unir dos movimientos, el uso de la IA del que se borra pasa al que queda. */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE uso_ia SET movimiento_id = :a WHERE movimiento_id = :de")
    int pasarUsoIa(@Param("de") Long de, @Param("a") Long a);

    /** La fecha del primer movimiento confirmado, de cualquier tipo: antes de eso no hay datos. Null si no hay. */
    @Query("""
            select min(m.fechaHora) from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO""")
    LocalDateTime primerFechaConfirmada();

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
