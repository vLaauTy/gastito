package ar.gastito.sueldo.escala;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.interpretacion.ContextoIa;
import ar.gastito.sueldo.SueldoProperties;
import ar.gastito.ticket.ImagenTicket;

/**
 * Le manda la foto de la hoja a Haiku una sola vez, que copia todas las columnas tal cual. Qué fila es qué y si los
 * números tienen formato de plata lo decide Java después.
 */
@Component
public class LectorFotoEscala {

    static final String PROMPT = "leer-escala";

    /** Lo que se leyó de la foto. */
    public sealed interface Resultado {
    }

    public record Leida(HojaEscala hoja) implements Resultado {
    }

    public record NoEsEscala() implements Resultado {
    }

    /** Borrosa, o con columnas sin un mes que se entienda. */
    public record Ilegible() implements Resultado {
    }

    private final ClienteIa ia;
    private final Prompts prompts;
    private final ImagenTicket imagenes;
    private final SueldoProperties properties;
    private final Clock clock;

    public LectorFotoEscala(ClienteIa ia, Prompts prompts, ImagenTicket imagenes, SueldoProperties properties,
            Clock clock) {
        this.ia = ia;
        this.prompts = prompts;
        this.imagenes = imagenes;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param pista lo que escribió el usuario con la foto, o null
     * @throws ar.gastito.ticket.ImagenInvalidaException si no se puede abrir
     * @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA
     */
    public Resultado leer(byte[] foto, String pista) {
        ImagenTicket.Imagen imagen = imagenes.preparar(foto);
        String instrucciones = prompts.cargar(PROMPT, Map.of("hoy", ContextoIa.hoy(LocalDateTime.now(clock))));
        String mensaje = pista == null || pista.isBlank() ? "El usuario mandó la foto sin texto."
                : "Texto que mandó el usuario con la foto: \"" + pista.strip() + "\"";
        LecturaEscalaIa leida = ia.extraerDeImagen(instrucciones, imagen.jpeg(), mensaje,
                properties.maxTokensEscala(), LecturaEscalaIa.class, PropositoIa.ESCALA).valor();
        return convertir(leida);
    }

    static Resultado convertir(LecturaEscalaIa leida) {
        if (leida == null) {
            return new Ilegible();
        }
        String tipo = leida.tipoDocumento() == null ? "" : leida.tipoDocumento().strip().toUpperCase(Locale.ROOT);
        if (!tipo.equals("ESCALA")) {
            return new NoEsEscala();
        }
        String problema = leida.problema() == null ? "" : leida.problema().strip();
        if (!problema.isEmpty() || leida.columnas() == null || leida.columnas().isEmpty()) {
            return new Ilegible();
        }
        List<HojaEscala.Columna> columnas = leida.columnas().stream().map(c -> new HojaEscala.Columna(
                MesHoja.leer(c.mes()).orElse(null),
                c.filas() == null ? List.of() : c.filas().stream()
                        .filter(f -> f.nombre() != null && !f.nombre().isBlank())
                        .map(f -> HojaEscala.Fila.de(f.nombre().strip(), f.valor())).toList()))
                .toList();
        // En la foto el mes está impreso arriba de cada columna: si no se entiende, no se adivina.
        if (columnas.stream().anyMatch(c -> c.mes() == null)) {
            return new Ilegible();
        }
        return new Leida(new HojaEscala(columnas));
    }
}
