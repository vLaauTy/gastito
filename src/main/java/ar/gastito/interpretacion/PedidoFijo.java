package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.util.List;

import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;

/**
 * "Agregá un fijo": lo que se entendió, para mostrar en la tarjeta de alta antes de guardarlo. Lo que falta se
 * pregunta con botones.
 *
 * @param nombre     corto ("Seguro moto")
 * @param monto      null si no se dijo
 * @param categoria  null si no se sabe: se elige entre las candidatas (o entre todas)
 * @param diaDelMes  null si no se dijo
 * @param variable   la luz, el gas: el monto cambia todos los meses
 * @param alias      cómo lo nombró el usuario si es distinto del nombre ("seguro de la moto"), o null: sirve para
 *                   reconocerlo cuando diga que lo pagó
 */
public record PedidoFijo(String nombre, BigDecimal monto, Categoria categoria, List<Categoria> candidatas,
        MedioPago medioPago, Cuenta cuenta, TarjetaCredito tarjeta, Integer diaDelMes, boolean bimestral,
        boolean variable, String alias) {

    public PedidoFijo {
        candidatas = candidatas == null ? List.of() : List.copyOf(candidatas);
        alias = aliasDistinto(nombre, alias);
    }

    public PedidoFijo(String nombre, BigDecimal monto, Categoria categoria, List<Categoria> candidatas,
            MedioPago medioPago, Cuenta cuenta, TarjetaCredito tarjeta, Integer diaDelMes, boolean bimestral,
            boolean variable) {
        this(nombre, monto, categoria, candidatas, medioPago, cuenta, tarjeta, diaDelMes, bimestral, variable, null);
    }

    /** Los que cambian todos los meses: si el nombre es uno de estos, el monto es variable aunque no se diga. */
    static final List<String> VARIABLES = List.of("luz", "gas", "agua", "electricidad", "edenor", "edesur",
            "metrogas", "aysa");

    static boolean esVariable(String nombre) {
        String n = Textos.normalizar(nombre);
        return VARIABLES.stream().anyMatch(v -> n.matches(".*\\b" + v + "\\b.*"));
    }

    /** El alias, en minúscula, solo si dice algo distinto del nombre. */
    static String aliasDistinto(String nombre, String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String limpio = alias.strip().toLowerCase(java.util.Locale.ROOT);
        return nombre != null && Textos.normalizar(nombre).equals(Textos.normalizar(limpio)) ? null : limpio;
    }

    public PedidoFijo conCategoria(Categoria elegida) {
        return new PedidoFijo(nombre, monto, elegida, List.of(), medioPago, cuenta, tarjeta, diaDelMes, bimestral,
                variable, alias);
    }

    public PedidoFijo conMonto(BigDecimal nuevo) {
        return new PedidoFijo(nombre, nuevo, categoria, candidatas, medioPago, cuenta, tarjeta, diaDelMes, bimestral,
                variable, alias);
    }

    public PedidoFijo conDia(Integer dia) {
        return new PedidoFijo(nombre, monto, categoria, candidatas, medioPago, cuenta, tarjeta, dia, bimestral,
                variable, alias);
    }

    public PedidoFijo conNombre(String nuevo) {
        return new PedidoFijo(nuevo, monto, categoria, candidatas, medioPago, cuenta, tarjeta, diaDelMes, bimestral,
                variable, alias == null ? nombre.toLowerCase(java.util.Locale.ROOT) : alias);
    }

    /** Con tarjeta de crédito se asocia la tarjeta y no la cuenta (la cuenta paga el resumen). */
    public PedidoFijo conMedio(MedioPago medio, TarjetaCredito tarjetaDelCatalogo) {
        boolean credito = medio != null && medio.esTarjetaDeCredito();
        return new PedidoFijo(nombre, monto, categoria, candidatas, medio, credito ? null : cuenta,
                credito ? tarjetaDelCatalogo : null, diaDelMes, bimestral, variable, alias);
    }

    public PedidoFijo conBimestral(boolean nuevo) {
        return new PedidoFijo(nombre, monto, categoria, candidatas, medioPago, cuenta, tarjeta, diaDelMes, nuevo,
                variable, alias);
    }
}
