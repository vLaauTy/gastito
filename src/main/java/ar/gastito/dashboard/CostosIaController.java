package ar.gastito.dashboard;

import java.time.Clock;
import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.ia.CostosIaService;

/**
 * La sección "Costos IA": el mes (con el anterior y la proyección a fin de mes), el desglose por propósito, el
 * costo de cada día, el historial de seis meses y las diez llamadas más caras. Todo en dólares, calculado en Java.
 */
@RestController
@RequestMapping("/api/costos-ia")
public class CostosIaController {

    private final CostosIaService costos;
    private final Clock clock;

    public CostosIaController(CostosIaService costos, Clock clock) {
        this.costos = costos;
        this.clock = clock;
    }

    @GetMapping("/panel")
    public CostosIaService.Panel panel(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return costos.panel(mes != null ? mes : YearMonth.now(clock));
    }
}
