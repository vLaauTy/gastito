package ar.gastito.movimiento;

import java.math.BigDecimal;

public record TotalPorCategoria(String categoria, String emoji, BigDecimal total) {
}
