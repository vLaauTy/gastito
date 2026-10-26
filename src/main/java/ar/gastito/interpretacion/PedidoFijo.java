package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.util.List;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;

/**
 * "Agregá un fijo": lo que se entendió, para mostrar en la tarjeta de alta antes de guardarlo.
 *
 * @param categoria  null si no se sabe: se elige entre las candidatas (o entre todas)
 * @param diaDelMes  null si no se dijo
 * @param variable   la luz, el gas: el monto cambia todos los meses
 */
public record PedidoFijo(String nombre, BigDecimal monto, Categoria categoria, List<Categoria> candidatas,
        MedioPago medioPago, Cuenta cuenta, TarjetaCredito tarjeta, Integer diaDelMes, boolean bimestral,
        boolean variable) {

    public PedidoFijo {
        candidatas = candidatas == null ? List.of() : List.copyOf(candidatas);
    }

    /** Los que cambian todos los meses: si el nombre es uno de estos, el monto es variable aunque no se diga. */
    static final List<String> VARIABLES = List.of("luz", "gas", "agua", "electricidad", "edenor", "edesur",
            "metrogas", "aysa");

    static boolean esVariable(String nombre) {
        String n = Textos.normalizar(nombre);
        return VARIABLES.stream().anyMatch(v -> n.matches(".*\\b" + v + "\\b.*"));
    }

    public PedidoFijo conCategoria(Categoria elegida) {
        return new PedidoFijo(nombre, monto, elegida, List.of(), medioPago, cuenta, tarjeta, diaDelMes, bimestral,
                variable);
    }
}
