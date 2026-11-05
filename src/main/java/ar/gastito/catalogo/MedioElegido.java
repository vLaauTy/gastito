package ar.gastito.catalogo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Un medio de pago elegido con un botón, con la tarjeta si es crédito. Con más de una tarjeta activa, "Tarjeta de
 * crédito" se reemplaza por una opción por tarjeta (la última que se usó primero), así elegir el medio ya dice la
 * tarjeta: un gasto con crédito no se guarda sin tarjeta.
 *
 * @param tarjeta con crédito, cuál; null con otro medio (o sin medio)
 */
public record MedioElegido(MedioPago medio, TarjetaCredito tarjeta) {

    /** Una opción para un botón. {@code valor} es el id del medio, o −id de la tarjeta. */
    public record Opcion(String nombre, long valor, MedioPago medio, TarjetaCredito tarjeta) {

        public boolean esTarjeta() {
            return tarjeta != null;
        }
    }

    /** El valor del botón que elige crédito con esa tarjeta. */
    public static long valor(TarjetaCredito tarjeta) {
        return -tarjeta.getId();
    }

    /** Los medios del catálogo, con una opción por tarjeta en lugar de "Tarjeta de crédito" si hay más de una. */
    public static List<Opcion> opciones(Catalogo catalogo) {
        List<Opcion> opciones = new ArrayList<>();
        for (MedioPago medio : catalogo.mediosPago()) {
            if (medio.esTarjetaDeCredito() && catalogo.tarjetas().size() > 1) {
                for (TarjetaCredito t : catalogo.tarjetas()) {
                    opciones.add(new Opcion(t.getNombre(), valor(t), medio, t));
                }
            } else {
                opciones.add(new Opcion(medio.getNombre(), medio.getId(), medio,
                        medio.esTarjetaDeCredito() ? catalogo.tarjetaUnica().orElse(null) : null));
            }
        }
        return opciones;
    }

    /**
     * Lo que significa el valor de un botón: un medio (con crédito, la única tarjeta, o null si hay varias y hay que
     * elegir) o una tarjeta. Vacío si ya no existe.
     */
    public static Optional<MedioElegido> de(long valor, Catalogo catalogo) {
        if (valor < 0) {
            return catalogo.tarjeta(-valor).flatMap(t -> catalogo.medioCredito().map(m -> new MedioElegido(m, t)));
        }
        return catalogo.mediosPago().stream().filter(m -> m.getId() == valor).findFirst()
                .map(m -> new MedioElegido(m, m.esTarjetaDeCredito() ? catalogo.tarjetaUnica().orElse(null) : null));
    }
}
