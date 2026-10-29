package ar.gastito.bot;

import java.util.Map;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/** El menú de botones fijo abajo del chat. Tocar un botón manda su texto, que se toma como el comando. */
public final class MenuFijo {

    static final String RESUMEN = "📋 Resumen";
    static final String DISPONIBLE = "💰 ¿Cuánto me queda?";
    static final String FIJOS = "📌 Fijos";
    static final String ME_DEBEN = "👥 Me deben";
    static final String HOY = "📊 Hoy";
    static final String MES = "📅 Mes";
    static final String AYUDA = "❓ Ayuda";
    /** Ya no están en el teclado, pero un teclado viejo que quedó en el chat los sigue mandando. */
    static final String COSTOS = "💸 Costos IA";
    static final String DASHBOARD = "🖥 Dashboard";

    private static final Map<String, String> COMANDOS = Map.of(
            RESUMEN, "/resumen",
            DISPONIBLE, "/disponible",
            FIJOS, "/fijos",
            ME_DEBEN, "/juntadas",
            HOY, "/hoy",
            MES, "/mes",
            AYUDA, "/ayuda",
            COSTOS, "/costos",
            DASHBOARD, "/dashboard");

    private MenuFijo() {
    }

    /** "📊 Hoy" → "/hoy". Null si el texto no es un botón del menú. */
    static String comando(String texto) {
        return COMANDOS.get(texto);
    }

    static ReplyKeyboardMarkup teclado() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(RESUMEN, DISPONIBLE))
                .keyboardRow(new KeyboardRow(FIJOS, ME_DEBEN))
                .keyboardRow(new KeyboardRow(HOY, MES, AYUDA))
                .resizeKeyboard(true)
                .isPersistent(true)
                .build();
    }
}
