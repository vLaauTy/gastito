package ar.gastito.dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
     */
    public record UltimoResumen(Long id, String tipo, LocalDate desde, LocalDate hasta, LocalDate fin,
            boolean parcial, boolean automatico, List<String> observaciones, String consejo,
            boolean textoIaDescartado, LocalDateTime creadoEn) {
    }

    /** 204 si todavía no se armó ninguno. */
    @GetMapping("/ultimo")
    public ResponseEntity<UltimoResumen> ultimo() {
        return resumenes.ultimo()
                .map(a -> {
                    Resumen r = a.resumen();
                    return ResponseEntity.ok(new UltimoResumen(r.getId(), r.getTipo().name(), r.getDesde(),
                            r.getHasta(), a.datos().periodo().fin(), r.isParcial(), r.isAutomatico(),
                            r.getObservaciones(), r.getConsejo(), r.isTextoIaDescartado(), r.getCreadoEn()));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
