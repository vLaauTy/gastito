package ar.gastito.sueldo.escala;

import java.text.Normalizer;
import java.util.Locale;

/** El nombre de una fila de la hoja, para compararlo: sin mayúsculas, acentos, espacios ni puntos. */
public final class NombreFila {

    private NombreFila() {
    }

    /** "A.I.S." → "ais"; "HORAS AL 50 %" → "horasal50"; "Viáticos" → "viaticos". */
    public static String clave(String nombre) {
        if (nombre == null) {
            return "";
        }
        return Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
