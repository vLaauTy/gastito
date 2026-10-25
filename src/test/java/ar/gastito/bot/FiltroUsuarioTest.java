package ar.gastito.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import ar.gastito.config.GastitoProperties;

class FiltroUsuarioTest {

    static final long PERMITIDO = 42;

    private final FiltroUsuario filtro = filtro(PERMITIDO);

    private static FiltroUsuario filtro(long permitido) {
        return new FiltroUsuario(new GastitoProperties(ZoneId.of("America/Argentina/Buenos_Aires"),
                new GastitoProperties.Telegram("token", "bot", permitido), null, null, null, null, null));
    }

    private static Update mensajeDe(Long userId) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        if (userId != null) {
            User user = mock(User.class);
            when(user.getId()).thenReturn(userId);
            when(message.getFrom()).thenReturn(user);
        }
        return update;
    }

    private static Update botonDe(long userId) {
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(callback.getFrom()).thenReturn(user);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callback);
        return update;
    }

    @Test
    void atiendeAlUsuarioPermitido() {
        assertThat(filtro.autorizado(mensajeDe(PERMITIDO))).isTrue();
        assertThat(filtro.autorizado(botonDe(PERMITIDO))).isTrue();
    }

    @Test
    void ignoraACualquierOtro() {
        assertThat(filtro.autorizado(mensajeDe(43L))).isFalse();
        assertThat(filtro.autorizado(botonDe(43))).isFalse();
    }

    @Test
    void ignoraMensajesSinRemitenteYOtrosTiposDeUpdate() {
        assertThat(filtro.autorizado(mensajeDe(null))).isFalse();
        assertThat(filtro.autorizado(mock(Update.class))).isFalse();
        assertThat(filtro.autorizado(null)).isFalse();
    }

    @Test
    void sinUsuarioConfiguradoNoAtiendeANadie() {
        assertThat(filtro(0).autorizado(mensajeDe(0L))).isFalse();
    }
}
