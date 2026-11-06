package ar.gastito.viaje;

import java.time.LocalDate;
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

    public ModoViaje(ViajeRepository viajes, PendienteViajeRepository pendientes) {
        this.viajes = viajes;
        this.pendientes = pendientes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> viajeDe(LocalDate dia) {
        return viajes.queIncluyen(dia).stream().findFirst().map(Viaje::getId);
    }

    @Override
    public void antesDeBorrar(Movimiento m) {
        pendientes.findByMovimientoId(m.getId()).forEach(PendienteViaje::desmarcar);
        pendientes.flush();
    }
}
