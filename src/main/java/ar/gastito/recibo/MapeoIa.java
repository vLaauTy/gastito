package ar.gastito.recibo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Lo que devuelve la IA al relacionar los renglones de un recibo con los conceptos del sueldo. */
public record MapeoIa(
        @JsonPropertyDescription("Un resultado por cada renglón del recibo.")
        List<Renglon> renglones) {

    public record Renglon(
            @JsonPropertyDescription("El número del renglón, como se lo pasaron.")
            int indice,

            @JsonPropertyDescription("El nombre del concepto del sueldo, exactamente como figura en la lista, "
                    + "o \"otro\" si no corresponde a ninguno.")
            String concepto) {
    }
}
