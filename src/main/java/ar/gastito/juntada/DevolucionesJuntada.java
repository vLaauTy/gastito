package ar.gastito.juntada;

import org.springframework.stereotype.Component;

import ar.gastito.movimiento.AntesDeBorrar;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.TipoMovimiento;

/**
 * Se borra una devolución de una juntada (↩️ Deshacer en el bot, Eliminar en el dashboard): la persona me vuelve a
 * deber lo que había pagado con ella. Va aparte de {@link JuntadaService} porque ese usa el servicio de movimientos,
 * que es el que llama a esto.
 */
@Component
class DevolucionesJuntada implements AntesDeBorrar {

    private final ParticipanteRepository participantes;

    DevolucionesJuntada(ParticipanteRepository participantes) {
        this.participantes = participantes;
    }

    @Override
    public void antesDeBorrar(Movimiento m) {
        if (m.getTipo() != TipoMovimiento.DEVOLUCION) {
            return;
        }
        participantes.findByDevolucionId(m.getId()).ifPresent(p -> {
            p.deshacerPago(m.getMonto());
            participantes.flush();
        });
    }
}
