package ar.gastito;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GastitoApplication {

    public static void main(String[] args) {
        SpringApplication.run(GastitoApplication.class, args);
    }
}
