package ar.gastito.ia;

import java.math.BigDecimal;

public record ResumenUsoIa(long llamadas, long tokensEntrada, long tokensSalida, BigDecimal costoUsd) {
}
