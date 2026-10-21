package ar.gastito.movimiento;

/** De dónde salió el movimiento. */
public enum Origen {
    TEXTO,
    AUDIO,
    TICKET,
    AVISO,
    RESUMEN,
    /** Cargado en un formulario del dashboard (por ahora, los ingresos). */
    DASHBOARD
}
