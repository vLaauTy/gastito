package ar.gastito.sueldo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * La cuenta del sueldo, sin base de datos. Todo en BigDecimal y redondeado a centavos en cada concepto:
 * <ul>
 * <li>Bruto remunerativo = fijos remunerativos + horas (horas × valor de la hora normal × factor).</li>
 * <li>Cada descuento = bruto × porcentaje.</li>
 * <li>Neto de haberes = bruto − descuentos. Neto total = neto de haberes + no remunerativos (que no tienen
 * descuentos).</li>
 * </ul>
 */
public final class CalculadoraSueldo {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private CalculadoraSueldo() {
    }

    /**
     * @param cantidad horas cargadas (solo en los conceptos por horas), o null
     * @param valor    el monto fijo o el porcentaje de la escala; null en las horas
     * @param ajustado si el importe se pisó a mano
     */
    public record Linea(Long conceptoId, String nombre, TipoConceptoSueldo tipo, BigDecimal cantidad,
            BigDecimal valor, BigDecimal importe, boolean ajustado) {
    }

    public record Resultado(BigDecimal valorHora, List<Linea> lineas, BigDecimal bruto, BigDecimal descuentos,
            BigDecimal netoHaberes, BigDecimal noRemunerativo, BigDecimal netoTotal) {
    }

    /**
     * @param conceptos los conceptos del sueldo (los inactivos no cuentan)
     * @param horas     las horas del mes por concepto (los que no están cuentan cero)
     * @param ajustes   importes que pisan lo calculado de un concepto, para un ajuste puntual del mes
     */
    public static Resultado calcular(List<ConceptoSueldo> conceptos, EscalaSueldo escala,
            Map<Long, BigDecimal> horas, Map<Long, BigDecimal> ajustes) {
        List<ConceptoSueldo> ordenados = conceptos.stream().filter(ConceptoSueldo::isActivo)
                .sorted(Comparator.comparingInt(ConceptoSueldo::getOrden)).toList();
        List<Linea> lineas = new ArrayList<>();
        BigDecimal bruto = BigDecimal.ZERO;
        BigDecimal noRemunerativo = BigDecimal.ZERO;
        // Primero lo que suma al bruto: los descuentos dependen de él.
        for (ConceptoSueldo c : ordenados) {
            if (c.getTipo() == TipoConceptoSueldo.DESCUENTO_PORCENTAJE) {
                continue;
            }
            Linea l = lineaDeHaber(c, escala, horas, ajustes);
            if (c.getTipo() == TipoConceptoSueldo.NO_REMUNERATIVO) {
                noRemunerativo = noRemunerativo.add(l.importe());
            } else {
                bruto = bruto.add(l.importe());
            }
            lineas.add(l);
        }
        BigDecimal descuentos = BigDecimal.ZERO;
        for (ConceptoSueldo c : ordenados) {
            if (c.getTipo() != TipoConceptoSueldo.DESCUENTO_PORCENTAJE) {
                continue;
            }
            BigDecimal porcentaje = escala.valorDe(c.getId());
            BigDecimal pisado = ajustes == null ? null : ajustes.get(c.getId());
            BigDecimal importe = pisado != null ? centavos(pisado)
                    : centavos(bruto.multiply(porcentaje).divide(CIEN, 10, RoundingMode.HALF_UP));
            descuentos = descuentos.add(importe);
            lineas.add(new Linea(c.getId(), c.getNombre(), c.getTipo(), null, porcentaje, importe, pisado != null));
        }
        lineas.sort(Comparator.comparingInt(l -> posicion(ordenados, l.conceptoId())));
        BigDecimal netoHaberes = bruto.subtract(descuentos);
        return new Resultado(escala.getValorHora(), List.copyOf(lineas), bruto, descuentos, netoHaberes,
                noRemunerativo, netoHaberes.add(noRemunerativo));
    }

    private static Linea lineaDeHaber(ConceptoSueldo c, EscalaSueldo escala, Map<Long, BigDecimal> horas,
            Map<Long, BigDecimal> ajustes) {
        BigDecimal pisado = ajustes == null ? null : ajustes.get(c.getId());
        if (c.getTipo() == TipoConceptoSueldo.HORAS) {
            BigDecimal cantidad = horas == null ? BigDecimal.ZERO : horas.getOrDefault(c.getId(), BigDecimal.ZERO);
            // Se multiplica por el numerador antes de dividir: 30 h nocturnas (8/60) dan exacto, sin 0,1333….
            BigDecimal calculado = centavos(cantidad.multiply(escala.getValorHora())
                    .multiply(BigDecimal.valueOf(c.getFactorNum()))
                    .divide(BigDecimal.valueOf(c.getFactorDen()), 10, RoundingMode.HALF_UP));
            return new Linea(c.getId(), c.getNombre(), c.getTipo(), cantidad, null,
                    pisado != null ? centavos(pisado) : calculado, pisado != null);
        }
        BigDecimal monto = escala.valorDe(c.getId());
        return new Linea(c.getId(), c.getNombre(), c.getTipo(), null, monto,
                pisado != null ? centavos(pisado) : centavos(monto), pisado != null);
    }

    private static int posicion(List<ConceptoSueldo> ordenados, Long conceptoId) {
        for (int i = 0; i < ordenados.size(); i++) {
            if (ordenados.get(i).getId().equals(conceptoId)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
