package ar.gastito.sueldo.escala;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un importe de la hoja de la escala ("$ 1.023.863,86"). La hoja tiene errores de tipeo con el separador ("$
 * 1.071,978,46"), así que se lee por grupos: el último separador seguido de dos dígitos (o uno) es el decimal, y los
 * demás tienen que agrupar de a tres, sea punto o coma. Lo que no cumple eso no es un importe válido: mejor avisar que
 * guardar un número leído mal.
 */
public final class ImporteHoja {

    /** "558154", "558154,5", "105822.50". */
    private static final Pattern SIN_MILES = Pattern.compile("(\\d+)(?:[.,](\\d{1,2}))?");
    /** "558.154", "1.023.863,86", "1.071,978,46", "1,023,863.86". */
    private static final Pattern CON_MILES = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+)(?:[.,](\\d{1,2}))?");

    private ImporteHoja() {
    }

    /** El importe con dos decimales; vacío si no tiene formato de plata (o es negativo). */
    public static Optional<BigDecimal> leer(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = texto.strip().replace("$", "").replaceAll("[\\s\\u00A0]", "");
        if (t.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = SIN_MILES.matcher(t);
        if (!m.matches()) {
            m = CON_MILES.matcher(t);
            if (!m.matches()) {
                return Optional.empty();
            }
        }
        String entero = m.group(1).replaceAll("[.,]", "");
        String decimales = m.group(2) == null ? "" : "." + m.group(2);
        return Optional.of(new BigDecimal(entero + decimales).setScale(2, RoundingMode.HALF_UP));
    }
}
