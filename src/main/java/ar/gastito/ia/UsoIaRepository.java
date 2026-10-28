package ar.gastito.ia;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsoIaRepository extends JpaRepository<UsoIa, Long> {

    @Query("""
            select new ar.gastito.ia.ResumenUsoIa(
                count(u), coalesce(sum(u.tokensEntrada), 0), coalesce(sum(u.tokensSalida), 0),
                coalesce(sum(u.costoUsd), 0))
            from UsoIa u
            where u.fechaHora >= :desde and u.fechaHora < :hasta""")
    ResumenUsoIa resumen(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    record UsoPorProposito(PropositoIa proposito, long llamadas, BigDecimal costoUsd) {
    }

    @Query("""
            select new ar.gastito.ia.UsoIaRepository$UsoPorProposito(u.proposito, count(u), coalesce(sum(u.costoUsd), 0))
            from UsoIa u
            where u.fechaHora >= :desde and u.fechaHora < :hasta
            group by u.proposito
            order by u.proposito""")
    List<UsoPorProposito> porProposito(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    record UsoDetallado(PropositoIa proposito, long llamadas, long tokensEntrada, long tokensSalida,
            BigDecimal costoUsd) {
    }

    /** Por propósito, con los tokens: es el desglose de la pantalla de costos. */
    @Query("""
            select new ar.gastito.ia.UsoIaRepository$UsoDetallado(u.proposito, count(u),
                coalesce(sum(u.tokensEntrada), 0), coalesce(sum(u.tokensSalida), 0), coalesce(sum(u.costoUsd), 0))
            from UsoIa u
            where u.fechaHora >= :desde and u.fechaHora < :hasta
            group by u.proposito""")
    List<UsoDetallado> detalladoPorProposito(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Las llamadas del período, de la más vieja a la más nueva. */
    List<UsoIa> findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByFechaHoraAscIdAsc(LocalDateTime desde,
            LocalDateTime hasta);

    /** Las más caras del período (con $pageable de N elementos, las N más caras). */
    List<UsoIa> findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByCostoUsdDescIdAsc(LocalDateTime desde,
            LocalDateTime hasta, Pageable pageable);
}
