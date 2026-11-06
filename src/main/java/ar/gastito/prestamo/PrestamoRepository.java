package ar.gastito.prestamo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrestamoRepository extends JpaRepository<Prestamo, Long> {

    @EntityGraph(attributePaths = {"cuotas", "movimiento"})
    Optional<Prestamo> findByMovimientoId(Long movimientoId);

    @EntityGraph(attributePaths = {"cuotas", "movimiento"})
    Optional<Prestamo> findConCuotasById(Long id);

    /** Los que valen (confirmados y, si salieron de un gasto, con el gasto guardado), del más nuevo al más viejo. */
    @Query("""
            select distinct p from Prestamo p left join fetch p.cuotas left join fetch p.movimiento m
            where p.estado <> ar.gastito.prestamo.EstadoPrestamo.PENDIENTE
              and (m is null or m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO)
            order by p.fecha desc, p.id desc""")
    List<Prestamo> vigentes();

    /** Los que todavía me deben algo. */
    @Query("""
            select distinct p from Prestamo p left join fetch p.cuotas left join fetch p.movimiento m
            where p.estado = ar.gastito.prestamo.EstadoPrestamo.ACTIVO
              and (m is null or m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO)
            order by p.fecha desc, p.id desc""")
    List<Prestamo> conDeuda();

    @Query("""
            select distinct p from Prestamo p left join fetch p.cuotas left join fetch p.movimiento m
            where p.viajeId = :viajeId and p.estado <> ar.gastito.prestamo.EstadoPrestamo.PENDIENTE
              and (m is null or m.estado = ar.gastito.movimiento.EstadoMovimiento.CONFIRMADO)
            order by p.fecha, p.id""")
    List<Prestamo> delViaje(@Param("viajeId") Long viajeId);

    /** Se borró un viaje: sus préstamos quedan sin viaje. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update Prestamo p set p.viajeId = null where p.viajeId = :viajeId")
    int desasociarViaje(@Param("viajeId") Long viajeId);
}
