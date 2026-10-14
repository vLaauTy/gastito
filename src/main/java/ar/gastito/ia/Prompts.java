package ar.gastito.ia;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Lee las instrucciones para la IA de src/main/resources/prompts/ y reemplaza los marcadores {{nombre}}.
 * Se leen en cada uso, así se pueden ajustar sin tocar código.
 */
@Component
public class Prompts {

    public String cargar(String nombre, Map<String, String> valores) {
        String texto = leer(nombre);
        for (Map.Entry<String, String> valor : valores.entrySet()) {
            texto = texto.replace("{{" + valor.getKey() + "}}", valor.getValue());
        }
        return texto;
    }

    private String leer(String nombre) {
        ClassPathResource recurso = new ClassPathResource("prompts/" + nombre + ".txt");
        try (InputStream in = recurso.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el prompt " + nombre, e);
        }
    }
}
