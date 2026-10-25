package ar.gastito.ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Importes y cantidades de un ticket. En un mismo ticket conviven los dos formatos: los productos con coma
 * decimal ("4.500,00", "897,60") y el TOTAL con punto ("30666.40"). La IA a veces los copia tal cual, así que se
 * aceptan los dos:
 * <ul>
 * <li>Con punto y coma, el que está más a la derecha es el decimal: "30.666,40" y "30,666.40".</li>
 * <li>Con un solo separador seguido de 1 o 2 dígitos, es decimal: "897,60", "30666.40", "1450,5".</li>
 * <li>Seguido de 3 dígitos y con hasta 3 adelante, o repetido, es de miles: "1.450", "1.234.567". "2250.123" es
 * decimal: un separador de miles no deja cuatro dígitos adelante.</li>
 * </ul>
 * El signo menos puede ir adelante, atrás ("250-") o entre paréntesis.
 */
final class Importes {

    private static final Pattern NUMERO = Pattern.compile("[0-9]+([.,][0-9]+)*");

    private Importes() {
    }

    /** Un importe con signo, con dos decimales. */
    static Optional<BigDecimal> importe(String texto) {
        return numero(texto, false).map(n -> n.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Una cantidad o un peso positivo ("2", "0,532", "1.5"). Con un solo separador siempre es decimal: en una
     * cantidad, "1.500" es un kilo y medio.
     */
    static Optional<BigDecimal> cantidad(String texto) {
        return numero(texto, true)
                .filter(n -> n.signum() > 0 && n.compareTo(new BigDecimal("9999999")) < 0)
                .map(n -> n.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    private static Optional<BigDecimal> numero(String texto, boolean separadorUnicoEsDecimal) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = texto.strip().replace("$", "").replace(" ", "").replace(" ", "");
        boolean negativo = t.startsWith("-") || t.endsWith("-") || (t.startsWith("(") && t.endsWith(")"));
        t = t.replaceAll("^[-(]+|[-)]+$", "");
        if (!NUMERO.matcher(t).matches()) {
            return Optional.empty();
        }
        BigDecimal valor = new BigDecimal(normalizar(t, separadorUnicoEsDecimal));
        return Optional.of(negativo ? valor.negate() : valor);
    }

    /** "30.666,40" → "30666.40". */
    private static String normalizar(String t, boolean separadorUnicoEsDecimal) {
        int punto = t.lastIndexOf('.');
        int coma = t.lastIndexOf(',');
        if (punto >= 0 && coma >= 0) {
            char decimal = punto > coma ? '.' : ',';
            char miles = decimal == '.' ? ',' : '.';
            return t.replace(String.valueOf(miles), "").replace(decimal, '.');
        }
        if (punto < 0 && coma < 0) {
            return t;
        }
        char separador = punto >= 0 ? '.' : ',';
        int ultimo = Math.max(punto, coma);
        boolean repetido = t.indexOf(separador) != ultimo;
        int decimales = t.length() - ultimo - 1;
        // De miles solo si agrupa bien: "1.450" sí, "2250.123" no (los miles dejan hasta tres dígitos adelante).
        boolean agrupaDeATres = decimales == 3 && ultimo <= 3;
        boolean esDecimal = !repetido && (separadorUnicoEsDecimal || !agrupaDeATres);
        return esDecimal ? t.replace(separador, '.') : t.replace(String.valueOf(separador), "");
    }
}
