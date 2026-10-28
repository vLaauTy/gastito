package ar.gastito.movimiento;

/** De dónde salió el movimiento. */
public enum Origen {
    TEXTO,
    AUDIO,
    TICKET,
    AVISO,
    RESUMEN,
    /** Cargado en un formulario del dashboard (por ahora, los ingresos). */
    DASHBOARD,
    /** Lo genera Gastito: el cobro de un gasto fijo, las cuotas de una compra. */
    AUTOMATICO,
    /** El neto de un recibo de sueldo leído por la IA. */
    RECIBO
}
