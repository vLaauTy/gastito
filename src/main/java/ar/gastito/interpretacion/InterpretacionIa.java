package ar.gastito.interpretacion;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que devuelve la IA al interpretar un mensaje (el schema JSON se genera desde este record).
 * Todo viene como texto y lo valida {@link ValidadorInterpretacion}: la IA puede equivocarse.
 * Los campos que el mensaje no menciona vienen vacíos. Cómo completar cada uno lo explica el prompt
 * ({@code interpretar-gasto.txt}); acá las descripciones son mínimas para no pagar dos veces los mismos tokens.
 */
public record InterpretacionIa(
        @JsonPropertyDescription("Una de las intenciones del prompt")
        String intencion,
        @JsonPropertyDescription("Solo dígitos y punto decimal")
        String monto,
        String descripcion,
        @JsonPropertyDescription("1 a 3 nombres exactos de categorías")
        List<String> categorias,
        @JsonPropertyDescription("alta, media o baja")
        String confianza,
        String medioPago,
        String cuenta,
        String fecha,
        @JsonPropertyDescription("HH:mm")
        String hora,
        String juntadaPersonas,
        List<String> juntadaNombres,
        @JsonPropertyDescription("Quién (DEVOLUCION o PERDONAR)")
        String devolucionPersona,
        @JsonPropertyDescription("De qué gasto (DEVOLUCION o PERDONAR)")
        String devolucionDe,
        String fijoDia,
        @JsonPropertyDescription("MENSUAL o BIMESTRAL")
        String fijoFrecuencia,
        @JsonPropertyDescription("si o no")
        String fijoVariable,
        String cuotasCantidad,
        String cuotasValorCuota,
        String fijoAlias,
        @JsonPropertyDescription("SUELDO, AGUINALDO o EXTRA")
        String claseIngreso,
        @JsonPropertyDescription("CORREGIR: MONTO, DESCRIPCION, FECHA, CATEGORIA o MEDIO")
        String campo,
        @JsonPropertyDescription("VER: qué lista")
        String ver,
        String horas,
        @JsonPropertyDescription("DUDOSO: 2 o 3 intenciones")
        List<String> opciones) {

    /** Sin los datos de juntadas, devoluciones ni gastos fijos. */
    public InterpretacionIa(String intencion, String monto, String descripcion, List<String> categorias,
            String confianza, String medioPago, String cuenta, String fecha, String hora) {
        this(intencion, monto, descripcion, categorias, confianza, medioPago, cuenta, fecha, hora, "", List.of(), "",
                "");
    }

    /** Sin los datos de gastos fijos ni de cuotas. */
    public InterpretacionIa(String intencion, String monto, String descripcion, List<String> categorias,
            String confianza, String medioPago, String cuenta, String fecha, String hora, String juntadaPersonas,
            List<String> juntadaNombres, String devolucionPersona, String devolucionDe) {
        this(intencion, monto, descripcion, categorias, confianza, medioPago, cuenta, fecha, hora, juntadaPersonas,
                juntadaNombres, devolucionPersona, devolucionDe, "", "", "", "", "");
    }

    /** Sin los datos de las intenciones que no son gastos (ingresos, correcciones, listas…). */
    public InterpretacionIa(String intencion, String monto, String descripcion, List<String> categorias,
            String confianza, String medioPago, String cuenta, String fecha, String hora, String juntadaPersonas,
            List<String> juntadaNombres, String devolucionPersona, String devolucionDe, String fijoDia,
            String fijoFrecuencia, String fijoVariable, String cuotasCantidad, String cuotasValorCuota) {
        this(intencion, monto, descripcion, categorias, confianza, medioPago, cuenta, fecha, hora, juntadaPersonas,
                juntadaNombres, devolucionPersona, devolucionDe, fijoDia, fijoFrecuencia, fijoVariable, cuotasCantidad,
                cuotasValorCuota, "", "", "", "", "", List.of());
    }

    /** La misma respuesta con otra intención: el usuario eligió qué quería entre las opciones de un DUDOSO. */
    public InterpretacionIa conIntencion(Intencion elegida) {
        return new InterpretacionIa(elegida.name(), monto, descripcion, categorias, confianza, medioPago, cuenta,
                fecha, hora, juntadaPersonas, juntadaNombres, devolucionPersona, devolucionDe, fijoDia, fijoFrecuencia,
                fijoVariable, cuotasCantidad, cuotasValorCuota, fijoAlias, claseIngreso, campo, ver, horas, opciones);
    }
}
