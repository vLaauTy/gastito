package ar.gastito.dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.resumen.DatosResumen;
import ar.gastito.resumen.Resumen;
import ar.gastito.resumen.ResumenService;

/** La tarjeta "Lo que notó la IA": las observaciones y el consejo del último resumen. */
@RestController
@RequestMapping("/api/resumenes")
public class ResumenesController {

    private final ResumenService resumenes;

    public ResumenesController(ResumenService resumenes) {
        this.resumenes = resumenes;
    }

    /**
     * @param hasta              último día con datos (en uno parcial, el día que se pidió)
     * @param fin                último día del período
     * @param textoIaDescartado  la IA escribió un número que no estaba en los datos: no hay observaciones
     * @param iaRespondio        se le pidió el texto a la IA y respondió (puede no haber notado nada nuevo)
     * @param sinConsejo         hay pocos datos: el texto que va en lugar del consejo; null si no
     * @param notas              notas al pie del resumen numérico
     */
    public record UltimoResumen(Long id, String tipo, LocalDate desde, LocalDate hasta, LocalDate fin,
            boolean parcial, boolean automatico, List<String> observaciones, String consejo,
            boolean textoIaDescartado, boolean iaRespondio, String sinConsejo, List<String> notas,
            LocalDateTime creadoEn) {
    }

    /** 204 si todavía no se armó ninguno. */
    @GetMapping("/ultimo")
    public ResponseEntity<UltimoResumen> ultimo() {
        return resumenes.ultimo()
                .map(a -> {
                    Resumen r = a.resumen();
                    DatosResumen d = a.datos();
                    return ResponseEntity.ok(new UltimoResumen(r.getId(), r.getTipo().name(), r.getDesde(),
                            r.getHasta(), d.periodo().fin(), r.isParcial(), r.isAutomatico(),
                            r.getObservaciones(), r.getConsejo(), r.isTextoIaDescartado(),
                            r.getUsoIaId() != null, d.pocosDatos() ? DatosResumen.POCOS_DATOS : null,
                            d.notas() == null ? List.of() : d.notas(), r.getCreadoEn()));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
