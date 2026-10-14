package ar.gastito.ia;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.Periodo;

/** Guarda cada llamada a la IA en uso_ia y arma el resumen de costos. */
@Service
public class RegistroUsoIa {

    private final UsoIaRepository repositorio;
    private final CalculadoraCostoIa calculadora;
    private final Clock clock;

    public RegistroUsoIa(UsoIaRepository repositorio, CalculadoraCostoIa calculadora, Clock clock) {
        this.repositorio = repositorio;
        this.calculadora = calculadora;
        this.clock = clock;
    }

    @Transactional
    public UsoIa registrar(String modelo, long tokensEntrada, long tokensSalida, PropositoIa proposito) {
        UsoIa uso = new UsoIa(LocalDateTime.now(clock), modelo, tokensEntrada, tokensSalida, proposito,
                calculadora.costo(modelo, tokensEntrada, tokensSalida));
        return repositorio.save(uso);
    }

    @Transactional
    public void asociarMovimiento(Long usoIaId, Long movimientoId) {
        repositorio.findById(usoIaId).ifPresent(uso -> uso.asociarMovimiento(movimientoId));
    }

    @Transactional(readOnly = true)
    public ResumenUsoIa resumenMesActual() {
        Periodo mes = Periodo.delMes(YearMonth.now(clock));
        return repositorio.resumen(mes.desde(), mes.hasta());
    }
}
