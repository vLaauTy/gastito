package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ar.gastito.sueldo.ConceptoSueldo;

/**
 * Lee las horas del mes de un mensaje, sin IA: "este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas". Cada tipo de
 * hora se reconoce por su nombre en la configuración (el número de "Horas al 50%" o la palabra de "Horas nocturnas"),
 * así que sigue andando si cambian los conceptos. Solo se activa si el mensaje dice que son horas del mes, para no
 * confundir un gasto ("2 horas de estacionamiento 1500") con un cálculo de sueldo.
 */
public final class ParserHorasSueldo {

    private static final Pattern INTENCION = Pattern.compile(
            "\\b(hice|hicimos|trabaje|trabajamos|este mes|el mes|horas extras?)\\b");
    private static final Pattern HABLA_DE_HORAS = Pattern.compile("\\b(horas?|hs|hrs|nocturn\\w*)\\b");
    private static final String NUMERO = "(\\d+(?:[.,]\\d+)?)";
    private static final String UNIDAD = "\\s*(?:h|hs|hrs|horas?)?\\s*";

    private ParserHorasSueldo() {
    }

    /**
     * @param deHoras los conceptos por horas activos
     * @return las horas por concepto, o vacío si el mensaje no habla de horas del mes o no se entendió ninguna
     */
    public static Optional<Map<Long, BigDecimal>> parsear(String texto, List<ConceptoSueldo> deHoras) {
        if (texto == null || texto.isBlank() || deHoras.isEmpty()) {
            return Optional.empty();
        }
        String t = normalizar(texto);
        if (!INTENCION.matcher(t).find() || !HABLA_DE_HORAS.matcher(t).find()) {
            return Optional.empty();
        }
        Map<Long, BigDecimal> horas = new HashMap<>();
        for (ConceptoSueldo c : deHoras) {
            Optional<BigDecimal> cantidad = buscar(t, normalizar(c.getNombre()));
            cantidad.ifPresent(h -> horas.put(c.getId(), h));
        }
        return horas.isEmpty() ? Optional.empty() : Optional.of(horas);
    }

    /**
     * Las horas de un comando posicional: "/calcular 10 6 30" son los primeros tres tipos de hora, en orden.
     *
     * @return las cantidades, o vacío si hay algo que no es un número
     */
    public static Optional<List<BigDecimal>> posicionales(String argumentos) {
        List<BigDecimal> cantidades = new ArrayList<>();
        for (String parte : argumentos.strip().split("[\\s;]+")) {
            if (parte.isEmpty()) {
                continue;
            }
            Optional<BigDecimal> n = numero(parte.replaceAll(",+$", ""));
            if (n.isEmpty()) {
                return Optional.empty();
            }
            cantidades.add(n.get());
        }
        return cantidades.isEmpty() ? Optional.empty() : Optional.of(cantidades);
    }

    /** Busca "10 horas al 50", "10 al 50%" o "30 nocturnas" según cómo se llame el concepto. */
    private static Optional<BigDecimal> buscar(String texto, String nombre) {
        Matcher porcentaje = Pattern.compile("(\\d+)\\s*%?").matcher(nombre);
        Pattern patron;
        if (porcentaje.find()) {
            patron = Pattern.compile(NUMERO + UNIDAD + "(?:al|a|x|por|con)\\s*" + porcentaje.group(1) + "\\b");
        } else {
            String clave = clave(nombre);
            if (clave.isEmpty()) {
                return Optional.empty();
            }
            // "30 nocturnas", "30 hs nocturnas", "30 horas de nocturnas".
            patron = Pattern.compile(NUMERO + UNIDAD + "(?:de\\s+)?" + Pattern.quote(clave) + "\\w*");
        }
        Matcher m = patron.matcher(texto);
        return m.find() ? numero(m.group(1)) : Optional.empty();
    }

    /** La palabra que distingue al concepto ("horas nocturnas" → "nocturn"). */
    private static String clave(String nombre) {
        for (String palabra : nombre.split("\\s+")) {
            if (palabra.length() >= 4 && !palabra.startsWith("hora")) {
                return palabra.substring(0, Math.min(palabra.length(), 7));
            }
        }
        return "";
    }

    private static Optional<BigDecimal> numero(String texto) {
        try {
            BigDecimal n = new BigDecimal(texto.replace(',', '.'));
            return n.signum() >= 0 ? Optional.of(n) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
    }
}
