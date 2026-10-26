package ar.gastito.interpretacion;

import java.util.Locale;

/** Qué quiere el usuario con un mensaje (o un audio). */
public enum Intencion {
    /** "3700 en pan": cargar un gasto. */
    GASTO,
    /** "¿cuánto gasté en pan?": una pregunta sobre sus gastos. */
    PREGUNTA,
    /** Un saludo, una prueba, otra cosa. */
    OTRO,
    /** No se sabe si es un gasto o una pregunta: se le pregunta al usuario con dos botones. */
    DUDOSO,
    /** "Pablo me pasó 8000", "me devolvieron 8000 del asado": alguien devuelve su parte de una juntada. */
    DEVOLUCION,
    /** "agregá un fijo: el seguro, 32900 con tarjeta el 4": dar de alta un gasto que se repite todos los meses. */
    FIJO;

    /** Lo que devolvió la IA; si no es ninguno de estos, OTRO. */
    static Intencion de(String texto) {
        if (texto == null) {
            return OTRO;
        }
        try {
            return valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTRO;
        }
    }
}
