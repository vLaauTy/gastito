package ar.gastito.resumen;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.ControlNumeros;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;

/**
 * Le pide a la IA las observaciones y el consejo a partir del JSON de los datos, y controla que no haya escrito un
 * número que no esté ahí. Si no se puede usar la IA, o inventó un número, el resumen sale solo con los números.
 */
@Component
public class RedactorResumen {

    private static final Logger log = LoggerFactory.getLogger(RedactorResumen.class);

    static final int MAXIMO_OBSERVACIONES = 4;
    static final int LARGO_MAXIMO_OBSERVACION = 400;
    static final int LARGO_MAXIMO_CONSEJO = 600;

    private final ClienteIa ia;
    private final Prompts prompts;
    private final GastitoProperties.Resumenes config;

    public RedactorResumen(ClienteIa ia, Prompts prompts, GastitoProperties properties) {
        this.ia = ia;
        this.prompts = prompts;
        this.config = properties.resumenes();
    }

    /**
     * @param observaciones vacía si no hay texto de la IA
     * @param consejo       null si no hay texto de la IA
     * @param descartado    la IA escribió un número que no está en los datos
     * @param usoIaId       el registro en uso_ia, o null si no se llamó a la IA
     */
    public record Redaccion(List<String> observaciones, String consejo, boolean descartado, Long usoIaId) {

        static Redaccion soloNumeros(boolean descartado, Long usoIaId) {
            return new Redaccion(List.of(), null, descartado, usoIaId);
        }

        public boolean tieneTexto() {
            return !observaciones.isEmpty() || consejo != null;
        }
    }

    /**
     * @param datosJson  lo que ve la IA
     * @param permitidos los números de esos datos (ver {@link ControlNumeros#numerosDe})
     */
    public Redaccion redactar(TipoResumen tipo, String datosJson, Set<BigDecimal> permitidos) {
        ClienteIa.Respuesta<TextoResumenIa> respuesta;
        try {
            String prompt = tipo == TipoResumen.SEMANAL ? "resumen-semanal" : "resumen-mensual";
            String modelo = tipo == TipoResumen.SEMANAL ? config.modeloSemanal() : config.modeloMensual();
            respuesta = ia.extraer(modelo, config.maxTokens(), prompts.cargar(prompt, Map.of()), datosJson,
                    TextoResumenIa.class, PropositoIa.RESUMEN);
        } catch (IaNoDisponibleException e) {
            log.warn("El resumen sale sin el texto de la IA: {}", e.getMessage());
            return Redaccion.soloNumeros(false, null);
        }

        List<String> observaciones = new ArrayList<>();
        for (String o : respuesta.valor().observaciones() == null ? List.<String>of() : respuesta.valor().observaciones()) {
            if (o != null && !o.isBlank() && observaciones.size() < MAXIMO_OBSERVACIONES) {
                observaciones.add(recortar(o.strip(), LARGO_MAXIMO_OBSERVACION));
            }
        }
        String consejo = respuesta.valor().consejo() == null || respuesta.valor().consejo().isBlank() ? null
                : recortar(respuesta.valor().consejo().strip(), LARGO_MAXIMO_CONSEJO);

        List<String> textos = new ArrayList<>(observaciones);
        if (consejo != null) {
            textos.add(consejo);
        }
        ControlNumeros.Resultado control = ControlNumeros.revisar(textos, permitidos);
        if (!control.valido()) {
            log.warn("La IA escribió números que no están en los datos del resumen {}: {}", tipo, control.inventados());
            return Redaccion.soloNumeros(true, respuesta.usoIaId());
        }
        return new Redaccion(List.copyOf(observaciones), consejo, false, respuesta.usoIaId());
    }

    private static String recortar(String texto, int largo) {
        return texto.length() <= largo ? texto : texto.substring(0, largo).strip() + "…";
    }
}
