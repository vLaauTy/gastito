package ar.gastito.recibo;

import static ar.gastito.recibo.Textos.pesos;
import static ar.gastito.recibo.Textos.porcentaje;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.SueldoProperties;
import ar.gastito.sueldo.TipoConceptoSueldo;

/**
 * Busca cosas raras en un recibo comparándolo con lo cobrado, con el cálculo del mes y con el recibo anterior.
 * Es todo cuenta de Java: la IA solo ayudó a saber qué concepto del sueldo es cada renglón.
 */
@Component
public class ControlRecibo {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final SueldoProperties tolerancias;

    public ControlRecibo(SueldoProperties tolerancias) {
        this.tolerancias = tolerancias;
    }

    public enum Tipo {
        /** Los totales del recibo no cierran. */
        NO_CIERRA,
        NETO_DISTINTO,
        CONCEPTO_DISTINTO,
        PORCENTAJE_DISTINTO,
        CONCEPTO_FALTA,
        CONCEPTO_NUEVO
    }

    public record Hallazgo(Tipo tipo, String texto) {
    }

    /**
     * @param conCalculo si ese mes había un cálculo para comparar los conceptos
     * @param conCobro   si ya estaba el sueldo cobrado para comparar el neto
     */
    public record Resultado(List<Hallazgo> hallazgos, boolean conCalculo, boolean conCobro) {

        public boolean sinNadaRaro() {
            return hallazgos.isEmpty();
        }
    }

    /**
     * @param aviso    lo que no cerró al leer el recibo, o null
     * @param cobro    lo cobrado de ese período, o null si todavía no se cargó
     * @param calculo  el cálculo del mes trabajado, o null
     * @param anterior los renglones del recibo del mes anterior, o null si no hay
     */
    public record Datos(TipoRecibo tipo, BigDecimal neto, String aviso, List<ReciboService.Renglon> conceptos,
            BigDecimal cobro, CalculoSueldo calculo, List<ReciboService.Renglon> anterior) {
    }

    public Resultado revisar(Datos d) {
        List<Hallazgo> hallazgos = new ArrayList<>();
        if (d.aviso() != null) {
            hallazgos.add(new Hallazgo(Tipo.NO_CIERRA, d.aviso()));
        }
        if (d.cobro() != null && d.cobro().subtract(d.neto()).abs().compareTo(tolerancias.toleranciaConcepto()) > 0) {
            hallazgos.add(new Hallazgo(Tipo.NETO_DISTINTO, "El neto del recibo es " + pesos(d.neto())
                    + " y vos cobraste " + pesos(d.cobro()) + " (diferencia "
                    + pesos(d.neto().subtract(d.cobro()).abs()) + ")."));
        }
        boolean conCalculo = d.calculo() != null && d.tipo() == TipoRecibo.SUELDO_MENSUAL;
        Set<String> yaMarcados = new HashSet<>();
        if (conCalculo) {
            contraElCalculo(d, hallazgos, yaMarcados);
        }
        if (d.anterior() != null && d.tipo() == TipoRecibo.SUELDO_MENSUAL) {
            contraElAnterior(d, hallazgos, yaMarcados);
        }
        return new Resultado(List.copyOf(hallazgos), conCalculo, d.cobro() != null);
    }

    private void contraElCalculo(Datos d, List<Hallazgo> hallazgos, Set<String> yaMarcados) {
        BigDecimal baseRemunerativo = d.conceptos().stream().filter(c -> c.grupo() == GrupoConcepto.REMUNERATIVO)
                .map(ReciboService.Renglon::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, BigDecimal> pagado = new LinkedHashMap<>();
        for (ReciboService.Renglon c : d.conceptos()) {
            if (c.conceptoId() != null) {
                pagado.merge(c.conceptoId(), c.importe(), BigDecimal::add);
            }
        }
        for (CalculoSueldo.Linea linea : d.calculo().getLineas()) {
            BigDecimal enRecibo = pagado.get(linea.getConceptoId());
            if (linea.getTipo() == TipoConceptoSueldo.DESCUENTO_PORCENTAJE) {
                descuento(linea, enRecibo, baseRemunerativo, hallazgos, yaMarcados);
            } else {
                haber(linea, enRecibo, hallazgos, yaMarcados);
            }
        }
    }

    private void haber(CalculoSueldo.Linea linea, BigDecimal enRecibo, List<Hallazgo> hallazgos,
            Set<String> yaMarcados) {
        BigDecimal esperado = linea.getImporte();
        if (enRecibo == null) {
            if (esperado.compareTo(tolerancias.toleranciaConcepto()) > 0) {
                yaMarcados.add(clave(linea.getConceptoId()));
                hallazgos.add(new Hallazgo(Tipo.CONCEPTO_DISTINTO, linea.getNombre()
                        + ": el recibo no lo trae y tu cálculo da " + pesos(esperado) + "."));
            }
            return;
        }
        if (enRecibo.subtract(esperado).abs().compareTo(tolerancias.toleranciaConcepto()) > 0) {
            String que = linea.getTipo() == TipoConceptoSueldo.HORAS ? "paga" : "dice";
            String contra = linea.getTipo() == TipoConceptoSueldo.HORAS ? "tu cálculo (" + horas(linea) + ") da"
                    : "tu cálculo da";
            hallazgos.add(new Hallazgo(Tipo.CONCEPTO_DISTINTO, linea.getNombre() + ": el recibo " + que + " "
                    + pesos(enRecibo) + " y " + contra + " " + pesos(esperado) + "."));
        }
    }

    private void descuento(CalculoSueldo.Linea linea, BigDecimal enRecibo, BigDecimal base,
            List<Hallazgo> hallazgos, Set<String> yaMarcados) {
        if (enRecibo == null) {
            if (linea.getImporte().compareTo(tolerancias.toleranciaConcepto()) > 0) {
                yaMarcados.add(clave(linea.getConceptoId()));
                hallazgos.add(new Hallazgo(Tipo.CONCEPTO_FALTA, linea.getNombre()
                        + ": no te lo descontaron y tu cálculo da " + pesos(linea.getImporte()) + "."));
            }
            return;
        }
        if (base.signum() <= 0) {
            return;
        }
        BigDecimal real = enRecibo.multiply(CIEN).divide(base, 2, RoundingMode.HALF_UP);
        if (real.subtract(linea.getValor()).abs().compareTo(tolerancias.toleranciaPorcentaje()) > 0) {
            hallazgos.add(new Hallazgo(Tipo.PORCENTAJE_DISTINTO, linea.getNombre() + ": te descontaron "
                    + porcentaje(real) + " y tu escala dice " + porcentaje(linea.getValor()) + "."));
        }
    }

    private static String horas(CalculoSueldo.Linea linea) {
        BigDecimal horas = linea.getCantidad() == null ? BigDecimal.ZERO : linea.getCantidad().stripTrailingZeros();
        return horas.toPlainString().replace('.', ',') + " h";
    }

    /**
     * Lo que estaba el mes anterior y ahora no, y al revés. Si el cálculo del mes ya dice que ese concepto vale cero
     * (cero horas al 100%), que falte no es raro; y si dice que vale algo, que aparezca tampoco: ya se comparó el
     * importe contra el cálculo.
     */
    private void contraElAnterior(Datos d, List<Hallazgo> hallazgos, Set<String> yaMarcados) {
        Map<String, String> ahora = nombres(d.conceptos());
        Map<String, String> antes = nombres(d.anterior());
        Set<String> enCero = new HashSet<>();
        Set<String> conImporte = new HashSet<>();
        if (d.calculo() != null) {
            for (CalculoSueldo.Linea l : d.calculo().getLineas()) {
                (l.getImporte().signum() == 0 ? enCero : conImporte).add(clave(l.getConceptoId()));
            }
        }
        for (Map.Entry<String, String> e : antes.entrySet()) {
            if (!ahora.containsKey(e.getKey()) && !yaMarcados.contains(e.getKey()) && !enCero.contains(e.getKey())) {
                hallazgos.add(new Hallazgo(Tipo.CONCEPTO_FALTA, e.getValue()
                        + " estaba en el recibo del mes anterior y ahora no está."));
            }
        }
        for (Map.Entry<String, String> e : ahora.entrySet()) {
            if (!antes.containsKey(e.getKey()) && !conImporte.contains(e.getKey())) {
                hallazgos.add(new Hallazgo(Tipo.CONCEPTO_NUEVO, e.getValue()
                        + " es un concepto nuevo: no estaba en el recibo del mes anterior."));
            }
        }
    }

    /** Un renglón se reconoce por el concepto del sueldo al que se mapeó o, si no, por su nombre. */
    private static Map<String, String> nombres(List<ReciboService.Renglon> renglones) {
        Map<String, String> nombres = new LinkedHashMap<>();
        for (ReciboService.Renglon r : renglones) {
            String clave = r.conceptoId() != null ? clave(r.conceptoId()) : "n" + Textos.claveDeMapeo(r.descripcion());
            nombres.putIfAbsent(clave, r.descripcion());
        }
        return nombres;
    }

    private static String clave(Long conceptoId) {
        return "c" + conceptoId;
    }
}
