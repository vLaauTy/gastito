package ar.gastito.resumen;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Lo que redacta la IA a partir de los datos del resumen (el schema JSON se genera desde este record). */
public record TextoResumenIa(
        @JsonPropertyDescription("De 2 a 4 observaciones cortas, una oración cada una, sobre los datos")
        List<String> observaciones,

        @JsonPropertyDescription("Un solo consejo concreto, con un número de los escenarios de ahorro")
        String consejo) {
}
