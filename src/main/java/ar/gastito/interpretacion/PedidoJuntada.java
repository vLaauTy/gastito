package ar.gastito.interpretacion;

import java.util.ArrayList;
import java.util.List;

/**
 * El gasto es una juntada: el monto es el total que pagué, entre {@code personas} (yo incluido). La división la hace
 * Java (ver ar.gastito.juntada.DivisionJuntada).
 *
 * @param nombres de los demás, si se dijeron
 */
public record PedidoJuntada(int personas, List<String> nombres) {

    static final int MAXIMO_PERSONAS = 50;

    public PedidoJuntada {
        nombres = List.copyOf(nombres);
    }

    /**
     * Lo que dijo la IA, controlado: los nombres sin vacíos ni repetidos, y al menos tantas personas como nombres
     * más yo. Null si no es una juntada (menos de 2 personas).
     */
    static PedidoJuntada de(String personasIa, List<String> nombresIa) {
        List<String> nombres = new ArrayList<>();
        for (String n : nombresIa == null ? List.<String>of() : nombresIa) {
            String limpio = n == null ? "" : n.strip();
            if (!limpio.isEmpty() && nombres.stream().noneMatch(limpio::equalsIgnoreCase)) {
                nombres.add(Textos.capitalizar(limpio));
            }
        }
        int personas = 0;
        if (personasIa != null && personasIa.strip().matches("\\d{1,3}")) {
            personas = Integer.parseInt(personasIa.strip());
        }
        personas = Math.max(personas, nombres.isEmpty() ? 0 : nombres.size() + 1);
        if (personas < 2 || personas > MAXIMO_PERSONAS) {
            return null;
        }
        return new PedidoJuntada(personas, nombres.subList(0, Math.min(nombres.size(), personas - 1)));
    }
}
