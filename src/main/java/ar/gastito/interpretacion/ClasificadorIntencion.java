package ar.gastito.interpretacion;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Las preguntas obvias se reconocen sin llamar a la IA: "¿cuánto gasté en kiosco?", "compará octubre con
 * septiembre". Salvo que el mensaje tenga un monto: "¿cuánto salió la nafta? 9000" es un gasto, y ahí decide la IA
 * (en la misma llamada que interpreta el gasto).
 */
final class ClasificadorIntencion {

    /** Empieza como una pregunta o un pedido de datos (ya sin tildes ni mayúsculas). */
    private static final Pattern COMIENZO_DE_PREGUNTA = Pattern.compile(
            "^(cuanto|cuanta|cuantos|cuantas|cual|cuales|que|en que|donde|cuando|como|compara|comparame|"
                    + "mostrame|decime|listame|lista)\\b");

    /**
     * Un monto: 3 o más cifras ("1500", "1.500", "1.500,50") o "15 mil" / "15k". No cuentan los días ("del 3 al
     * 10") ni los años ("en 2026").
     */
    private static final Pattern MONTO = Pattern.compile(
            "(?<![\\d/])(?:\\d{1,3}(?:\\.\\d{3})+|\\d{3,})(?:,\\d{1,2})?(?![\\d/])|\\d+\\s*(?:mil|k)\\b");
    private static final Pattern ANIO = Pattern.compile("^(?:19|20)\\d{2}$");

    private ClasificadorIntencion() {
    }

    /** Pedir algo, no darlo de alta ni sacarlo ("agregá un fijo", "cancelá las cuotas"). */
    private static final Pattern CAMBIO = Pattern.compile(
            "\\b(agreg\\w*|sum\\w*|anot\\w*|sac\\w*|borr\\w*|cancel\\w*|elimin\\w*|baja)\\b");
    private static final Pattern FIJOS = Pattern.compile(
            "\\bfijos\\b.*|.*\\b(falta|faltan|pendientes?)\\b.*\\bfijos?\\b.*");
    private static final Pattern DEUDAS = Pattern.compile("\\b(quien|quienes|que|cuanto) me (debe|deben)\\b");
    private static final Pattern IA = Pattern.compile("\\b(ia|inteligencia artificial)\\b");
    private static final Pattern COSTO = Pattern.compile("\\b(cuanto|costo|costos|gaste|gastado|llevo|lleva|va)\\b");
    private static final Pattern RESUMEN = Pattern.compile("\\bresumen\\b");
    private static final Pattern DEL_MES = Pattern.compile("\\b(mes|mensual)\\b");
    private static final Pattern CUOTAS = Pattern.compile(
            "\\b(que|cuales|mis|ver|mostrame|lista|pendientes|quedan|tengo)\\b.*\\bcuotas\\b|^cuotas$");

    /**
     * Pedidos de listas que no necesitan la IA: "¿qué fijos me faltan pagar?", "¿quién me debe plata?", "¿cuánto
     * llevo de IA este mes?", "mandame el resumen de la semana", "¿qué cuotas tengo?". Con un monto o un pedido de
     * cambio ("agregá…", "cancelá…") no: eso lo decide la IA.
     */
    static Optional<ValidadorInterpretacion.Lista> pedidoDeLista(String texto) {
        if (texto == null || texto.isBlank() || tieneMonto(texto)) {
            return Optional.empty();
        }
        String t = Textos.normalizar(texto).replaceAll("[¿?¡!.,]", " ").replaceAll("\\s+", " ").strip();
        if (CAMBIO.matcher(t).find()) {
            return Optional.empty();
        }
        if (DEUDAS.matcher(t).find()) {
            return Optional.of(ValidadorInterpretacion.Lista.DEUDAS);
        }
        if (IA.matcher(t).find() && COSTO.matcher(t).find()) {
            return Optional.of(ValidadorInterpretacion.Lista.COSTOS_IA);
        }
        if (RESUMEN.matcher(t).find()) {
            return Optional.of(DEL_MES.matcher(t).find() ? ValidadorInterpretacion.Lista.RESUMEN_MES
                    : ValidadorInterpretacion.Lista.RESUMEN_SEMANA);
        }
        if (FIJOS.matcher(t).find() && !t.contains("cuanto")) {
            return Optional.of(ValidadorInterpretacion.Lista.FIJOS);
        }
        if (CUOTAS.matcher(t).find() && !t.contains("cuanto")) {
            return Optional.of(ValidadorInterpretacion.Lista.CUOTAS);
        }
        return Optional.empty();
    }

    static boolean esPreguntaObvia(String texto) {
        if (texto == null || texto.isBlank() || tieneMonto(texto)) {
            return false;
        }
        String t = Textos.normalizar(texto);
        return texto.contains("¿") || texto.strip().endsWith("?") || COMIENZO_DE_PREGUNTA.matcher(t).find();
    }

    static boolean tieneMonto(String texto) {
        var m = MONTO.matcher(texto.toLowerCase());
        while (m.find()) {
            if (!ANIO.matcher(m.group()).matches()) {
                return true;
            }
        }
        return false;
    }
}
