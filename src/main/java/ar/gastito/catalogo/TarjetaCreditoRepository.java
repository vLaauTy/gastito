package ar.gastito.catalogo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TarjetaCreditoRepository extends JpaRepository<TarjetaCredito, Long> {

    /** La primera activa (la más vieja). Para elegir la de un gasto, mejor {@link Catalogo#tarjetaUnica()}. */
    Optional<TarjetaCredito> findFirstByActivaTrueOrderById();

    /** Las activas, la del último gasto confirmado primero (las que nunca se usaron, al final). */
    @Query(value = """
            SELECT t.* FROM tarjeta t
            LEFT JOIN (SELECT tarjeta_id, MAX(fecha_hora) AS ultima FROM movimiento
                       WHERE tipo = 'GASTO' AND estado = 'CONFIRMADO' AND tarjeta_id IS NOT NULL
                       GROUP BY tarjeta_id) u ON u.tarjeta_id = t.id
            WHERE t.activa = TRUE
            ORDER BY CASE WHEN u.ultima IS NULL THEN 1 ELSE 0 END, u.ultima DESC, t.id
            """, nativeQuery = true)
    List<TarjetaCredito> activasPorUso();

    List<TarjetaCredito> findAllByOrderByActivaDescIdAsc();

    boolean existsByNombreIgnoreCase(String nombre);
}
