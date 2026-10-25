package ar.gastito.ia;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import ar.gastito.ticket.Importes;

/**
 * Control de números inventados (regla 2: la IA cuenta, no calcula). Cada monto, porcentaje o cantidad que
 * aparece en un texto de la IA tiene que estar en los datos que le pasó Java. Tolerancias, para los redondeos que
 * hace al redactar:
 * <ul>
 * <li>Montos (100 o más): hasta un 2% ("unos $15.000" por $14.800).</li>
 * <li>Porcentajes: hasta 1 punto.</li>
 * <li>Números chicos (cantidades, días, decimales): hasta 0,05; es decir, exactos.</li>
 * </ul>
 * El signo no cuenta: "bajó un 33%" sale de una variación de -33.
 */
public final class ControlNumeros {

    static final BigDecimal TOLERANCIA_MONTO = new BigDecimal("0.02");
    static final BigDecimal TOLERANCIA_PORCENTAJE = BigDecimal.ONE;
    static final BigDecimal TOLERANCIA_CHICOS = new BigDecimal("0.05");
    private static final BigDecimal CIEN = new BigDecimal("100");

    /**
     * Un número en el texto: "14.800", "$ 1.234,50", "4,7", "12%", "15 mil", "1,5 millones". Las horas ("21:00")
     * se sacan antes, porque no son datos.
     */
    private static final Pattern NUMERO = Pattern.compile(
            "(\\d[\\d.,]*\\d|\\d)\\s*(%|por ciento|mil(?:lones|l[oó]n)?\\b|k\\b)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORA = Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");
    private static final Pattern FECHA_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private ControlNumeros() {
    }

    /** Un número leído del texto. */
    record Numero(BigDecimal valor, boolean porcentaje, String comoEsta) {
    }

    /** @param inventados los números del texto que no están en los datos, como estaban escritos */
    public record Resultado(boolean valido, List<String> inventados) {
    }

    /** Revisa todos los números del texto contra los permitidos. */
    public static Resultado revisar(String texto, Collection<BigDecimal> permitidos) {
        List<String> inventados = new ArrayList<>();
        for (Numero n : numeros(texto)) {
            boolean esta = permitidos.stream().anyMatch(p -> coincide(n, p));
            if (!esta) {
                inventados.add(n.comoEsta());
            }
        }
        return new Resultado(inventados.isEmpty(), inventados);
    }

    /** Revisa varios textos juntos (las observaciones y el consejo). */
    public static Resultado revisar(Collection<String> textos, Collection<BigDecimal> permitidos) {
        return revisar(String.join("\n", textos), permitidos);
    }

    static List<Numero> numeros(String texto) {
        List<Numero> numeros = new ArrayList<>();
        if (texto == null) {
            return numeros;
        }
        Matcher m = NUMERO.matcher(HORA.matcher(texto).replaceAll(" "));
        while (m.find()) {
            Optional<BigDecimal> valor = Importes.importe(m.group(1));
            if (valor.isEmpty()) {
                continue;
            }
            String sufijo = m.group(2) == null ? "" : m.group(2).toLowerCase();
            BigDecimal v = valor.get().abs();
            if (sufijo.startsWith("mill")) {
                v = v.multiply(new BigDecimal("1000000"));
            } else if (sufijo.equals("mil") || sufijo.equals("k")) {
                v = v.multiply(new BigDecimal("1000"));
            }
            boolean porcentaje = sufijo.equals("%") || sufijo.equals("por ciento");
            numeros.add(new Numero(v, porcentaje, m.group().strip()));
        }
        return numeros;
    }

    static boolean coincide(Numero n, BigDecimal permitido) {
        BigDecimal p = permitido.abs();
        BigDecimal diferencia = n.valor().subtract(p).abs();
        if (n.porcentaje()) {
            return diferencia.compareTo(TOLERANCIA_PORCENTAJE) <= 0;
        }
        if (p.compareTo(CIEN) >= 0) {
            return diferencia.compareTo(p.multiply(TOLERANCIA_MONTO)) <= 0;
        }
        return diferencia.compareTo(TOLERANCIA_CHICOS) <= 0;
    }

    /**
     * Todos los números de un JSON: los valores numéricos, los que aparecen dentro de los textos ("Gastar un 10%
     * menos") y el año, el mes y el día de las fechas.
     */
    public static Set<BigDecimal> numerosDe(JsonNode json) {
        Set<BigDecimal> numeros = new HashSet<>();
        juntar(json, numeros);
        return numeros;
    }

    private static void juntar(JsonNode nodo, Set<BigDecimal> numeros) {
        if (nodo == null || nodo.isNull()) {
            return;
        }
        if (nodo.isNumber()) {
            numeros.add(nodo.decimalValue());
        } else if (nodo.isTextual()) {
            String texto = nodo.asText();
            Matcher fecha = FECHA_ISO.matcher(texto);
            while (fecha.find()) {
                try {
                    LocalDate dia = LocalDate.parse(fecha.group());
                    numeros.add(BigDecimal.valueOf(dia.getYear()));
                    numeros.add(BigDecimal.valueOf(dia.getMonthValue()));
                    numeros.add(BigDecimal.valueOf(dia.getDayOfMonth()));
                } catch (DateTimeParseException e) {
                    // no era una fecha
                }
            }
            numeros(FECHA_ISO.matcher(texto).replaceAll(" ")).forEach(n -> numeros.add(n.valor()));
        } else {
            nodo.forEach(hijo -> juntar(hijo, numeros));
        }
    }
}
