package ar.gastito.recibo;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.interpretacion.ContextoIa;

/**
 * Le manda el recibo a la IA y devuelve lo que leyó, sin validar. Un PDF con texto va como texto (cuesta menos que
 * una imagen); un escaneo o una foto, como imagen.
 */
@Component
public class LectorRecibo {

    static final String PROMPT = "leer-recibo";

    private final ClienteIa ia;
    private final Prompts prompts;
    private final String modelo;
    private final long maxTokens;

    public LectorRecibo(ClienteIa ia, Prompts prompts, GastitoProperties properties, RecibosProperties recibos) {
        this.ia = ia;
        this.prompts = prompts;
        this.modelo = properties.ia().modelo();
        this.maxTokens = recibos.maxTokens();
    }

    /**
     * @param texto el texto del PDF, ya sin datos personales
     * @param pista lo que escribió el usuario con el archivo, o null
     * @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA
     */
    public ClienteIa.Respuesta<LecturaReciboIa> leerTexto(String texto, String pista, LocalDateTime ahora) {
        String mensaje = "Texto del recibo, sacado del PDF:\n\n" + texto + "\n\n" + mensaje(pista);
        return ia.extraer(modelo, maxTokens, instrucciones(ahora), mensaje, LecturaReciboIa.class,
                PropositoIa.RECIBO);
    }

    /** @param jpeg la foto o la página, ya achicada */
    public ClienteIa.Respuesta<LecturaReciboIa> leerImagen(byte[] jpeg, String pista, LocalDateTime ahora) {
        return ia.extraerDeImagen(instrucciones(ahora), jpeg, mensaje(pista), maxTokens, LecturaReciboIa.class,
                PropositoIa.RECIBO);
    }

    private String instrucciones(LocalDateTime ahora) {
        return prompts.cargar(PROMPT, Map.of("hoy", ContextoIa.hoy(ahora)));
    }

    static String mensaje(String pista) {
        return pista == null || pista.isBlank()
                ? "El usuario mandó el recibo sin texto."
                : "Texto que mandó el usuario con el recibo: \"" + pista.strip() + "\"";
    }
}
