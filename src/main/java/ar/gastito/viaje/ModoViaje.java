package ar.gastito.viaje;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.AntesDeBorrar;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.ViajeActivo;

/**
 * Lo que los movimientos necesitan saber de los viajes, sin depender del servicio (que a su vez usa el de
 * movimientos): de qué viaje es un día (el modo viaje) y qué pasa con un pendiente cuando se borra el gasto que lo
 * pagaba (vuelve a estar sin pagar).
 */
@Component
public class ModoViaje implements ViajeActivo, AntesDeBorrar {

    private final ViajeRepository viajes;
    private final PendienteViajeRepository pendientes;
    private final Clock clock;

    public ModoViaje(ViajeRepository viajes, PendienteViajeRepository pendientes, Clock clock) {
        this.viajes = viajes;
        this.pendientes = pendientes;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> viajeDe(LocalDate dia) {
        return viajes.queIncluyen(dia).stream().findFirst().map(Viaje::getId);
    }

    /**
     * El viaje cuyo nombre aparece entero en el texto ("el pasaje a Bariloche"). Si hay varios con ese nombre, el que
     * todavía no terminó y empieza antes; si ya terminaron todos, el más reciente.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Long> viajeNombradoEn(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String t = " " + ViajeService.normalizar(texto) + " ";
        LocalDate hoy = LocalDate.now(clock);
        List<Viaje> nombrados = viajes.findAllByOrderByDesdeDesc().stream()
                .filter(v -> !ViajeService.normalizar(v.getNombre()).isEmpty()
                        && t.contains(" " + ViajeService.normalizar(v.getNombre()) + " "))
                .toList();
        return nombrados.stream().filter(v -> !v.getHasta().isBefore(hoy)).min(Comparator.comparing(Viaje::getDesde))
                .or(() -> nombrados.stream().findFirst()).map(Viaje::getId);
    }

    @Override
    public void antesDeBorrar(Movimiento m) {
        pendientes.findByMovimientoId(m.getId()).forEach(PendienteViaje::desmarcar);
        pendientes.flush();
    }
}
