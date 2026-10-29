package ar.gastito.interpretacion;

import java.util.Locale;

/**
 * Qué quiere el usuario con un mensaje (o un audio). Todo lo que se puede hacer con un comando se puede pedir así,
 * dicho como sea: la IA lo reconoce en la misma llamada que interpreta un gasto.
 */
public enum Intencion {
    /** "3700 en pan", "de luz pagué 87.500": cargar un gasto (o el pago de un fijo). */
    GASTO,
    /** "¿cuánto gasté en pan?": una pregunta sobre sus datos. */
    PREGUNTA,
    /** Un saludo, una prueba, otra cosa. */
    OTRO,
    /** No se sabe qué quiere: se le pregunta con botones entre las opciones más probables. */
    DUDOSO,
    /** "Pablo me pasó 8000", "me devolvieron 8000 del asado": alguien devuelve su parte de una juntada. */
    DEVOLUCION,
    /** "Cobré el sueldo, 980 mil en Galicia": un ingreso (sueldo, aguinaldo o extra). */
    INGRESO,
    /** "Agregá un fijo: el seguro de la moto, 32 mil por mes": dar de alta un gasto fijo. */
    ALTA_FIJO,
    /** "Sacá Netflix de los fijos": dar de baja un gasto fijo. */
    BAJA_FIJO,
    /** "El último era 3500, no 3700": corregir lo último que cargó. */
    CORREGIR,
    /** "Borrá lo último que cargué". */
    BORRAR,
    /** "Este mes hice 10 horas al 50…": calcular el sueldo del mes. */
    CALCULO_SUELDO,
    /** "Cancelá las cuotas de las zapatillas". */
    CANCELAR_CUOTAS,
    /** "Lo de Pablo dalo por perdido": dar por perdida la parte de alguien en una juntada. */
    PERDONAR,
    /** "¿Qué fijos me faltan?", "mandame el resumen de la semana": ver una lista o un resumen. */
    VER;

    /** Lo que devolvió la IA; si no es ninguno de estos, OTRO. "FIJO" es el nombre viejo de ALTA_FIJO. */
    public static Intencion de(String texto) {
        if (texto == null) {
            return OTRO;
        }
        String t = texto.strip().toUpperCase(Locale.ROOT);
        if (t.equals("FIJO")) {
            return ALTA_FIJO;
        }
        try {
            return valueOf(t);
        } catch (IllegalArgumentException e) {
            return OTRO;
        }
    }
}
