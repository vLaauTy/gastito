package ar.gastito.fijo;

import java.math.BigDecimal;
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

    /** @param aproximado con cuánto se tilda "Pagado" desde el recordatorio */
    public record Vencido(Long fijoId, String nombre, int diaVencimiento, BigDecimal aproximado) {
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
        List<Vencido> vencidos = vencidos();
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

    /** Los fijos de este mes que ya vencieron y siguen en "Falta" (también para rehacer el recordatorio). */
    @Transactional(readOnly = true)
    public List<Vencido> vencidos() {
        LocalDate hoy = LocalDate.now(clock);
        return fijos.checklist(YearMonth.from(hoy)).stream()
                .filter(i -> i.estado() == GastoFijoService.EstadoChecklist.FALTA && i.vencimiento() != null
                        && i.vencimiento().isBefore(hoy))
                .map(i -> new Vencido(i.fijoId(), i.nombre(), i.vencimiento().getDayOfMonth(), i.aproximado()))
                .toList();
    }
}
