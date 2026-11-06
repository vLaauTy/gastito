package ar.gastito.prestamo;

public enum EstadoPrestamo {
    /** Cargado solo (sin un gasto): falta confirmarlo con el botón. */
    PENDIENTE,
    /** Me debe al menos una cuota. */
    ACTIVO,
    /** Ya me pagó todo. */
    TERMINADO
}
