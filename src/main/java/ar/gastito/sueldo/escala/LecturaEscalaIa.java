package ar.gastito.sueldo.escala;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que devuelve la IA al leer la foto de la hoja de la escala (el schema JSON se genera desde este record). Los
 * valores vienen como texto, copiados como están impresos: los lee y los controla Java ({@link ImporteHoja}).
 */
public record LecturaEscalaIa(
        @JsonPropertyDescription("ESCALA si es una tabla de valores salariales por mes; OTRO si es otra cosa.")
        String tipoDocumento,

        @JsonPropertyDescription("Vacío si se lee bien. \"borrosa\" si no se pueden leer los valores.")
        String problema,

        @JsonPropertyDescription("Una por cada columna de mes, en el orden impreso.")
        List<Columna> columnas) {

    public record Columna(
            @JsonPropertyDescription("El encabezado del mes como está impreso. Ejemplo: oct-26")
            String mes,

            @JsonPropertyDescription("Cada fila con valor en esa columna, de arriba hacia abajo.")
            List<Fila> filas) {
    }

    public record Fila(
            @JsonPropertyDescription("El nombre de la fila como está impreso. Ejemplo: A.I.S.")
            String nombre,

            @JsonPropertyDescription("El valor exactamente como está impreso, con $, puntos y comas. Ejemplo: "
                    + "$ 1.023.863,86")
            String valor) {
    }
}
