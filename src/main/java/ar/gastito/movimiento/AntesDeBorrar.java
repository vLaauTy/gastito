package ar.gastito.movimiento;

/**
 * Lo que tiene que pasar antes de borrar un gasto de verdad (↩️ Deshacer, Eliminar en el dashboard) en otros
 * paquetes que lo referencian: un préstamo que salió de ese pago, un pendiente de un viaje que se pagó con él. Puede
 * frenar el borrado con una {@link MovimientoEspecialException}.
 */
public interface AntesDeBorrar {

    void antesDeBorrar(Movimiento m);
}
