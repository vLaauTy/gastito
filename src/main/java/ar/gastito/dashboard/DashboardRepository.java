package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import ar.gastito.movimiento.Movimiento;

/**
 * Consultas del dashboard. Solo cuentan gastos confirmados (tipo GASTO, estado CONFIRMADO): los pendientes,
 * cancelados, pagos de tarjeta, pases entre cuentas y devoluciones no. Los rangos de fecha son [desde, hasta).
 */
interface DashboardRepository extends Repository<Movimiento, Long> {

    record TotalPorDia(int dia, BigDecimal total) {
    }

    record TotalCategoria(Long categoriaId, String nombre, String emoji, BigDecimal total, long cantidad) {
    }

    /** Una combinación de tarjeta, cuenta y "es efectivo" con su total; el servicio las junta en billeteras. */
    record FilaBilletera(Long tarjetaId, String tarjeta, String colorTarjeta, Long cuentaId, String cuenta,
            String colorCuenta, Boolean efectivo, BigDecimal total, long cantidad) {
    }

    @Query("""
            select count(m) from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta""")
    long cantidadGastos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query("""
            select coalesce(sum(m.monto), 0) from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta""")
    BigDecimal totalGastos(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Solo los días que tienen gastos; el servicio completa los días en cero. */
    @Query("""
            select new ar.gastito.dashboard.DashboardRepository$TotalPorDia(day(m.fechaHora), sum(m.monto))
            from Movimiento m
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            group by day(m.fechaHora)""")
    List<TotalPorDia> totalesPorDia(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query("""
            select new ar.gastito.dashboard.DashboardRepository$TotalCategoria(
                c.id, c.nombre, c.emoji, sum(m.monto), count(m))
            from Movimiento m join m.categoria c
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            group by c.id, c.nombre, c.emoji
            order by sum(m.monto) desc, c.nombre""")
    List<TotalCategoria> totalesPorCategoria(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query("""
            select new ar.gastito.dashboard.DashboardRepository$FilaBilletera(
                t.id, t.nombre, t.color, c.id, c.nombre, c.color, mp.efectivo, sum(m.monto), count(m))
            from Movimiento m left join m.tarjeta t left join m.cuenta c left join m.medioPago mp
            where m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO
              and m.tipo = ar.gastito.movimiento.TipoMovimiento.GASTO
              and m.fechaHora >= :desde and m.fechaHora < :hasta
            group by t.id, t.nombre, t.color, c.id, c.nombre, c.color, mp.efectivo""")
    List<FilaBilletera> filasBilletera(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
