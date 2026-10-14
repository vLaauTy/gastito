package ar.gastito.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.config.GastitoProperties;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RegistroUsoIa.class, CalculadoraCostoIa.class, RegistroUsoIaTest.Reloj.class})
@EnableConfigurationProperties(GastitoProperties.class)
class RegistroUsoIaTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 10, 13, 16, 42).atZone(ARGENTINA).toInstant(), ARGENTINA);
        }
    }

    @Autowired
    RegistroUsoIa registro;

    @Test
    void guardaTokensYCostoCalculado() {
        UsoIa uso = registro.registrar("claude-haiku-5-5", 800, 120, PropositoIa.TEXTO);

        assertThat(uso.getId()).isNotNull();
        assertThat(uso.getFechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 13, 16, 42));
        assertThat(uso.getCostoUsd()).isEqualByComparingTo("0.00014");
    }

    @Test
    void resumenDelMes() {
        registro.registrar("claude-haiku-5-5", 800, 120, PropositoIa.TEXTO);
        registro.registrar("claude-haiku-5-5", 1, 1, PropositoIa.TEXTO);

        ResumenUsoIa resumen = registro.resumenMesActual();

        assertThat(resumen.llamadas()).isEqualTo(2);
        assertThat(resumen.tokensEntrada()).isEqualTo(801);
        assertThat(resumen.tokensSalida()).isEqualTo(121);
        assertThat(resumen.costoUsd()).isEqualByComparingTo("0.0001406");
    }
}
