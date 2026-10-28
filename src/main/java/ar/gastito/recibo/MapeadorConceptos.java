package ar.gastito.recibo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.sueldo.ConceptoSueldo;

/**
 * Relaciona cada renglón de un recibo con un concepto del sueldo. Primero mira lo que ya se mapeó antes y los
 * nombres que coinciden exacto; solo lo que queda va a la IA, y lo que ella responde se guarda para los recibos
 * siguientes (también los "otro").
 */
@Component
public class MapeadorConceptos {

    private static final Logger log = LoggerFactory.getLogger(MapeadorConceptos.class);
    static final String PROMPT = "mapear-conceptos-recibo";
    private static final String OTRO = "otro";

    private final MapeoConceptoReciboRepository mapeos;
    private final ClienteIa ia;
    private final Prompts prompts;

    public MapeadorConceptos(MapeoConceptoReciboRepository mapeos, ClienteIa ia, Prompts prompts) {
        this.mapeos = mapeos;
        this.ia = ia;
        this.prompts = prompts;
    }

    /**
     * @param renglones lo que figura en el recibo
     * @param conceptos los conceptos activos del sueldo
     * @param conIa     si puede preguntarle a la IA por lo que no reconoce (sin ella, lo desconocido queda como
     *                  "otro" sin guardarse)
     * @return el concepto de cada renglón, en el mismo orden; null es "otro"
     */
    @Transactional
    public List<Long> mapear(List<ReciboLeido.Concepto> renglones, List<ConceptoSueldo> conceptos, boolean conIa) {
        Map<String, Long> porNombre = new HashMap<>();
        conceptos.stream().filter(ConceptoSueldo::isActivo)
                .forEach(c -> porNombre.putIfAbsent(Textos.claveDeMapeo(c.getNombre()), c.getId()));
        List<String> claves = renglones.stream().map(r -> Textos.claveDeMapeo(r.descripcion())).toList();
        Map<String, Long> guardados = new HashMap<>();
        Map<String, Boolean> sabidos = new HashMap<>();
        for (MapeoConceptoRecibo m : mapeos.findByClaveIn(claves)) {
            guardados.put(m.getClave(), m.getConceptoSueldoId());
            sabidos.put(m.getClave(), true);
        }

        Map<String, Long> resultado = new LinkedHashMap<>();
        List<Integer> pendientes = new ArrayList<>();
        for (int i = 0; i < renglones.size(); i++) {
            String clave = claves.get(i);
            if (resultado.containsKey(clave)) {
                continue;
            }
            if (sabidos.containsKey(clave)) {
                resultado.put(clave, guardados.get(clave));
            } else if (porNombre.containsKey(clave)) {
                resultado.put(clave, porNombre.get(clave));
            } else {
                resultado.put(clave, null);
                pendientes.add(i);
            }
        }
        if (!pendientes.isEmpty() && conIa) {
            preguntarALaIa(renglones, claves, pendientes, conceptos, resultado);
        }
        return claves.stream().map(resultado::get).toList();
    }

    private void preguntarALaIa(List<ReciboLeido.Concepto> renglones, List<String> claves, List<Integer> pendientes,
            List<ConceptoSueldo> conceptos, Map<String, Long> resultado) {
        List<ConceptoSueldo> activos = conceptos.stream().filter(ConceptoSueldo::isActivo).toList();
        Map<String, ConceptoSueldo> porNombreExacto = activos.stream()
                .collect(Collectors.toMap(c -> Textos.claveDeMapeo(c.getNombre()), c -> c, (a, b) -> a));
        String lista = activos.stream().map(c -> "- " + c.getNombre() + " — " + c.getTipo().name())
                .collect(Collectors.joining("\n"));
        StringBuilder mensaje = new StringBuilder("Renglones del recibo (indice. descripción · grupo):\n");
        for (int i = 0; i < pendientes.size(); i++) {
            ReciboLeido.Concepto r = renglones.get(pendientes.get(i));
            mensaje.append(i).append(". ").append(r.descripcion()).append(" · ").append(r.grupo().name())
                    .append('\n');
        }
        MapeoIa respuesta;
        try {
            respuesta = ia.extraer(prompts.cargar(PROMPT, Map.of("conceptos", lista)), mensaje.toString(),
                    MapeoIa.class, PropositoIa.RECIBO).valor();
        } catch (IaNoDisponibleException e) {
            // El recibo se guarda igual; la próxima vez se vuelve a preguntar.
            log.warn("No pude mapear los conceptos del recibo con la IA: {}", e.getMessage());
            return;
        }
        if (respuesta == null || respuesta.renglones() == null) {
            return;
        }
        Map<Integer, String> dichos = new HashMap<>();
        respuesta.renglones().forEach(r -> dichos.putIfAbsent(r.indice(), r.concepto()));
        for (int i = 0; i < pendientes.size(); i++) {
            String dicho = dichos.get(i);
            if (dicho == null) {
                continue;
            }
            // La IA solo puede elegir de la lista: lo que no coincide exacto es "otro".
            ConceptoSueldo elegido = OTRO.equalsIgnoreCase(dicho.strip()) ? null
                    : porNombreExacto.get(Textos.claveDeMapeo(dicho));
            String clave = claves.get(pendientes.get(i));
            Long id = elegido == null ? null : elegido.getId();
            resultado.put(clave, id);
            mapeos.save(new MapeoConceptoRecibo(clave, id));
        }
    }
}
