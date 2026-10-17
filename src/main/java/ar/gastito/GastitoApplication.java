package ar.gastito;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationEvent;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GastitoApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GastitoApplication.class);
        app.addListeners(GastitoApplication::usarZonaHorariaConfigurada);
        app.run(args);
    }

    /**
     * Usa gastito.zona-horaria como zona por defecto de la JVM, antes de que se abra la base.
     * En Windows la JVM toma "America/Buenos_Aires", un alias viejo que el driver de PostgreSQL
     * manda al conectar y que el servidor rechaza ("invalid value for parameter TimeZone").
     */
    private static void usarZonaHorariaConfigurada(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            String zona = prepared.getEnvironment().getProperty("gastito.zona-horaria");
            if (zona != null && !zona.isBlank()) {
                TimeZone.setDefault(TimeZone.getTimeZone(zona));
            }
        }
    }
}
