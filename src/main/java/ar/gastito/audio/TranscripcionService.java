package ar.gastito.audio;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.ia.Prompts;

/**
 * Transcribe un audio pasándole a Whisper los nombres de las categorías, medios de pago y cuentas, para que
 * escriba bien esas palabras ("Galicia", "Mercado Pago", "débito"…).
 */
@Service
public class TranscripcionService {

    static final String PROMPT = "transcribir-audio";

    private final ClienteTranscripcion cliente;
    private final CatalogoService catalogos;
    private final Prompts prompts;

    public TranscripcionService(ClienteTranscripcion cliente, CatalogoService catalogos, Prompts prompts) {
        this.cliente = cliente;
        this.catalogos = catalogos;
        this.prompts = prompts;
    }

    /**
     * @return lo que se escuchó; vacío si no se escuchó nada
     * @throws TranscripcionNoDisponibleException si el servidor de Whisper no se pudo usar
     */
    public String transcribir(byte[] audio, String nombreArchivo) {
        return cliente.transcribir(audio, nombreArchivo, prompt(catalogos.cargar()));
    }

    String prompt(Catalogo catalogo) {
        return prompts.cargar(PROMPT, Map.of(
                "categorias", nombres(catalogo.categorias()),
                "mediosPago", nombres(catalogo.mediosPago()),
                "cuentas", nombres(catalogo.cuentas()))).strip();
    }

    private static String nombres(List<? extends ConPalabrasClave> opciones) {
        return opciones.stream().map(ConPalabrasClave::getNombre).collect(Collectors.joining(", "));
    }
}
