package ar.gastito;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.bot.GastitoBot;
import ar.gastito.bot.ProcesadorMensajes;

/** Levanta la aplicación completa con H2 en memoria, sin conectarse a Telegram. */
@SpringBootTest
@ActiveProfiles("test")
class GastitoApplicationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void levantaSinTelegram() {
        assertThat(context.getBean(ProcesadorMensajes.class)).isNotNull();
        assertThat(context.getBeanNamesForType(GastitoBot.class)).isEmpty();
    }
}
