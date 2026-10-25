package ar.gastito.resumen;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Lo que redacta la IA a partir de los datos del resumen (el schema JSON se genera desde este record). */
public record TextoResumenIa(
        @JsonPropertyDescription("De 0 a 3 observaciones cortas, una oración cada una, que aporten algo que el "
                + "resumen numérico no muestra. Vacía si no hay nada nuevo para decir")
        List<String> observaciones,

        @JsonPropertyDescription("Un solo consejo concreto, con un número de los escenarios de ahorro. Vacío si "
                + "pocosDatos es true")
        String consejo) {
}
