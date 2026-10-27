package ar.gastito.interpretacion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Gasté 90.000 en luz, 70000 en gas y 31500 en Netflix": separa un mensaje en varios gastos, sin usar la IA
 * (que "interpreta, no calcula": separar por coma o "y" es estructura, no cálculo). Si no se puede separar con
 * confianza (menos de dos partes, o alguna sin un monto propio), se devuelve el texto entero sin tocar y el
 * mensaje sigue el camino de un solo gasto.
 */
public final class DivisorDeGastos {

    private static final Pattern SEPARADOR = Pattern.compile("(?i)\\s*,\\s*|\\s+y\\s+");

    private DivisorDeGastos() {
    }

    public static List<String> separar(String texto) {
        if (texto == null || texto.isBlank()) {
            return Collections.singletonList(texto);
        }
        List<String> partes = Arrays.stream(SEPARADOR.split(texto)).map(String::strip).filter(s -> !s.isEmpty())
                .toList();
        if (partes.size() < 2 || !partes.stream().allMatch(DivisorDeGastos::tieneMontoPropio)) {
            return List.of(texto);
        }
        return partes;
    }

    private static boolean tieneMontoPropio(String parte) {
        return Arrays.stream(parte.split("\\s+")).anyMatch(palabra -> Montos.parsear(palabra).isPresent());
    }
}
