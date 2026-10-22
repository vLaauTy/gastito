package ar.gastito.bot;

import java.util.Map;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/** El menú de botones fijo abajo del chat. Tocar un botón manda su texto, que se toma como el comando. */
public final class MenuFijo {

    static final String HOY = "📊 Hoy";
    static final String MES = "📅 Mes";
    static final String COSTOS = "💸 Costos IA";
    static final String DASHBOARD = "🖥 Dashboard";
    static final String AYUDA = "❓ Ayuda";

    private static final Map<String, String> COMANDOS = Map.of(
            HOY, "/hoy",
            MES, "/mes",
            COSTOS, "/costos",
            DASHBOARD, "/dashboard",
            AYUDA, "/ayuda");

    private MenuFijo() {
    }

    /** "📊 Hoy" → "/hoy". Null si el texto no es un botón del menú. */
    static String comando(String texto) {
        return COMANDOS.get(texto);
    }

    static ReplyKeyboardMarkup teclado() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(HOY, MES, COSTOS))
                .keyboardRow(new KeyboardRow(DASHBOARD, AYUDA))
                .resizeKeyboard(true)
                .isPersistent(true)
                .build();
    }
}
