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
    DUDOSO;

    /** Lo que devolvió la IA; si no es ninguno de los cuatro, OTRO. */
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
