package ar.gastito.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TiempoConfig {

    /** Reloj en la zona horaria del usuario: todo "ahora" y "hoy" sale de acá. */
    @Bean
    public Clock clock(GastitoProperties properties) {
        return Clock.system(properties.zonaHoraria());
    }
}
