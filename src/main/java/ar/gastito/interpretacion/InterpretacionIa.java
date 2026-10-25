package ar.gastito.interpretacion;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que devuelve la IA al interpretar un mensaje (el schema JSON se genera desde este record).
 * Todo viene como texto y lo valida {@link ValidadorInterpretacion}: la IA puede equivocarse.
 * Los campos que el mensaje no menciona vienen vacíos.
 */
public record InterpretacionIa(
        @JsonPropertyDescription("GASTO si el mensaje carga un gasto; PREGUNTA si pregunta por sus gastos o ingresos; "
                + "OTRO si es un saludo, una prueba u otra cosa; DUDOSO si podría ser un gasto o una pregunta")
        String intencion,

        @JsonPropertyDescription("Monto en pesos, solo dígitos y punto decimal, sin separador de miles. "
                + "Ejemplos: 3700, 1250.50, 15000. Vacío si el mensaje no dice cuánto fue.")
        String monto,

        @JsonPropertyDescription("Descripción corta de qué se compró o pagó, con mayúscula inicial. "
                + "Ejemplo: Pan de pancho y papas")
        String descripcion,

        @JsonPropertyDescription("Entre 1 y 3 nombres de la lista de categorías, de la más probable a la menos probable")
        List<String> categorias,

        @JsonPropertyDescription("Qué tan segura es la primera categoría: alta, media o baja")
        String confianza,

        @JsonPropertyDescription("Exactamente uno de los nombres de la lista de medios de pago, o vacío si no se menciona")
        String medioPago,

        @JsonPropertyDescription("Exactamente uno de los nombres de la lista de cuentas, o vacío si no se menciona")
        String cuenta,

        @JsonPropertyDescription("Cuándo fue, tal como lo dice el mensaje: hoy, ayer, anteayer, un día de la semana "
                + "(lunes, martes, miércoles, jueves, viernes, sábado, domingo) o una fecha dd/mm o dd/mm/aaaa. "
                + "Vacío si no se menciona.")
        String fecha,

        @JsonPropertyDescription("Hora en formato HH:mm si el mensaje la menciona; si no, vacío")
        String hora) {
}
