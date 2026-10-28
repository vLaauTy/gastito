package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.cuota.PlanCuotasService;
import ar.gastito.cuota.VistaPlanCuotas;
import ar.gastito.juntada.EstadoParticipante;
import ar.gastito.juntada.JuntadaService;
import ar.gastito.juntada.VistaJuntada;

/** Juntadas y cuotas para el dashboard. Solo lectura: se cargan y se cobran desde el bot. */
@RestController
@RequestMapping("/api")
public class JuntadasCuotasController {

    static final int MESES_COMPROMETIDOS = 6;

    private final JuntadaService juntadas;
    private final PlanCuotasService cuotas;

    public JuntadasCuotasController(JuntadaService juntadas, PlanCuotasService cuotas) {
        this.juntadas = juntadas;
        this.cuotas = cuotas;
    }

    /** @param aCobrar lo que todavía me deben entre todas las juntadas abiertas */
    public record Juntadas(BigDecimal aCobrar, int personasQueDeben, List<VistaJuntada> abiertas,
            List<VistaJuntada> cerradas) {
    }

    @GetMapping("/juntadas")
    public Juntadas juntadas() {
        List<VistaJuntada> todas = juntadas.todas();
        List<VistaJuntada> abiertas = todas.stream().filter(j -> !j.pendientes().isEmpty()).toList();
        List<VistaJuntada> cerradas = todas.stream().filter(j -> j.pendientes().isEmpty()).toList();
        BigDecimal aCobrar = abiertas.stream().map(VistaJuntada::aCobrar).reduce(BigDecimal.ZERO, BigDecimal::add);
        int personas = (int) abiertas.stream().flatMap(j -> j.participantes().stream())
                .filter(p -> p.estado() == EstadoParticipante.PENDIENTE).count();
        return new Juntadas(aCobrar, personas, abiertas, cerradas);
    }

    /** @param comprometido las cuotas que todavía no ocurrieron, mes a mes desde el actual */
    public record Cuotas(List<VistaPlanCuotas> planes, BigDecimal totalComprometido,
            List<PlanCuotasService.MesComprometido> comprometido) {
    }

    @GetMapping("/cuotas")
    public Cuotas cuotas() {
        List<PlanCuotasService.MesComprometido> meses = cuotas.comprometido(MESES_COMPROMETIDOS);
        BigDecimal total = meses.stream().map(PlanCuotasService.MesComprometido::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Cuotas(cuotas.activos(), total, meses);
    }
}
