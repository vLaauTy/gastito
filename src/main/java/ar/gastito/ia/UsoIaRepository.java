package ar.gastito.ia;

import java.time.LocalDateTime;

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
}
