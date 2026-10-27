package ar.gastito.fijo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El recordatorio agrupado de fijos vencidos sin cargar ("Te falta cargar: luz (vence el 10), gas (vence el 12)"),
 * como máximo cada {@code gastito.fijos.intervalo-recordatorio} (3 días por defecto).
 */
@Service
public class RecordatorioFijoService {

    public record Vencido(String nombre, int diaVencimiento) {
    }

    private final GastoFijoService fijos;
    private final RecordatorioFijoRepository recordatorios;
    private final FijosProperties properties;
    private final Clock clock;

    public RecordatorioFijoService(GastoFijoService fijos, RecordatorioFijoRepository recordatorios,
            FijosProperties properties, Clock clock) {
        this.fijos = fijos;
        this.recordatorios = recordatorios;
        this.properties = properties;
        this.clock = clock;
    }

    /** Vacío si no hay nada vencido, o si ya se mandó un recordatorio hace menos del intervalo. */
    @Transactional
    public Optional<List<Vencido>> siCorresponde() {
        LocalDate hoy = LocalDate.now(clock);
        List<Vencido> vencidos = fijos.checklist(YearMonth.from(hoy)).stream()
                .filter(i -> i.estado() == GastoFijoService.EstadoChecklist.FALTA && i.vencimiento().isBefore(hoy))
                .map(i -> new Vencido(i.nombre(), i.vencimiento().getDayOfMonth())).toList();
        if (vencidos.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime ahora = LocalDateTime.now(clock);
        Optional<RecordatorioFijo> ultimo = recordatorios.findTopByOrderByIdDesc();
        if (ultimo.isPresent() && ultimo.get().getEnviadoEn().plus(properties.intervaloRecordatorio()).isAfter(ahora)) {
            return Optional.empty();
        }
        recordatorios.save(new RecordatorioFijo(ahora));
        return Optional.of(vencidos);
    }
}
