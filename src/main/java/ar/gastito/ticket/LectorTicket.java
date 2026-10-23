package ar.gastito.ticket;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.interpretacion.ContextoIa;
import ar.gastito.movimiento.MovimientoService;

/** Le manda la foto (ya achicada) a la IA con el catálogo y los ejemplos, y devuelve lo que leyó sin validar. */
@Component
public class LectorTicket {

    static final String PROMPT = "leer-ticket";

    private final ClienteIa ia;
    private final Prompts prompts;
    private final MovimientoService movimientos;
    private final long maxTokens;

    public LectorTicket(ClienteIa ia, Prompts prompts, MovimientoService movimientos, GastitoProperties properties) {
        this.ia = ia;
        this.prompts = prompts;
        this.movimientos = movimientos;
        this.maxTokens = properties.ticket().maxTokens();
    }

    /**
     * @param pista el texto que mandó el usuario con la foto ("efectivo", "con la de Galicia"), o null
     * @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA
     */
    public ClienteIa.Respuesta<LecturaTicketIa> leer(byte[] jpeg, String pista, Catalogo catalogo,
            LocalDateTime ahora) {
        String instrucciones = prompts.cargar(PROMPT, Map.of(
                "categorias", ContextoIa.categorias(catalogo.categorias()),
                "mediosPago", ContextoIa.lista(catalogo.mediosPago()),
                "cuentas", ContextoIa.lista(catalogo.cuentas()),
                "ejemplos", ContextoIa.ejemplos(movimientos.ultimasConfirmaciones()),
                "hoy", ContextoIa.hoy(ahora)));
        return ia.extraerDeImagen(instrucciones, jpeg, mensaje(pista), maxTokens, LecturaTicketIa.class,
                PropositoIa.TICKET);
    }

    static String mensaje(String pista) {
        return pista == null || pista.isBlank()
                ? "El usuario mandó la foto sin texto."
                : "Texto que mandó el usuario con la foto: \"" + pista.strip() + "\"";
    }
}
