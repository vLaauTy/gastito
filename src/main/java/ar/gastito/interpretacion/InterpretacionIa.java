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
                + "DEVOLUCION si alguien le devolvió plata (su parte de una juntada); "
                + "FIJO si pide agregar un gasto que se repite todos los meses (un gasto fijo); "
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
        String hora,
        @JsonPropertyDescription("Si pagó por un grupo (una juntada: 'éramos 6', 'con Martín, Sofi y Pablo'): cuántas "
                + "personas eran en total, contándolo a él, solo dígitos. Vacío si no es una juntada.")
        String juntadaPersonas,
        @JsonPropertyDescription("En una juntada, los nombres de los demás si los dice ('Martín', 'Sofi'). Vacío si no "
                + "dice nombres o no es una juntada.")
        List<String> juntadaNombres,
        @JsonPropertyDescription("Si intencion es DEVOLUCION: quién le pasó la plata ('Pablo'), o vacío si no lo dice")
        String devolucionPersona,
        @JsonPropertyDescription("Si intencion es DEVOLUCION: de qué gasto era la plata, como lo dice ('el asado'), o "
                + "vacío")
        String devolucionDe,
        @JsonPropertyDescription("Si intencion es FIJO: qué día del mes se cobra, solo dígitos del 1 al 31 ('el 4' → "
                + "4). Vacío si no lo dice.")
        String fijoDia,
        @JsonPropertyDescription("Si intencion es FIJO: MENSUAL, o BIMESTRAL si dice que es cada dos meses. Vacío si "
                + "no es FIJO.")
        String fijoFrecuencia,
        @JsonPropertyDescription("Si intencion es FIJO: si si el monto cambia todos los meses (la luz, el gas), no si "
                + "es siempre el mismo. Vacío si no es FIJO.")
        String fijoVariable) {

    /** Sin los datos de juntadas, devoluciones ni gastos fijos. */
    public InterpretacionIa(String intencion, String monto, String descripcion, List<String> categorias,
            String confianza, String medioPago, String cuenta, String fecha, String hora) {
        this(intencion, monto, descripcion, categorias, confianza, medioPago, cuenta, fecha, hora, "", List.of(), "",
                "");
    }

    /** Sin los datos de gastos fijos. */
    public InterpretacionIa(String intencion, String monto, String descripcion, List<String> categorias,
            String confianza, String medioPago, String cuenta, String fecha, String hora, String juntadaPersonas,
            List<String> juntadaNombres, String devolucionPersona, String devolucionDe) {
        this(intencion, monto, descripcion, categorias, confianza, medioPago, cuenta, fecha, hora, juntadaPersonas,
                juntadaNombres, devolucionPersona, devolucionDe, "", "", "");
    }
}
