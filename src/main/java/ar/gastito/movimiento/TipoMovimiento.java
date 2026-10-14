package ar.gastito.movimiento;

public enum TipoMovimiento {
    /** Cuenta como gasto. */
    GASTO,
    /** Sueldo o trabajo particular. Cuenta como ingreso. */
    INGRESO,
    /** Alguien devuelve plata (por ejemplo su parte de una juntada). No cuenta como ingreso. */
    DEVOLUCION,
    /** Pago del resumen de la tarjeta. No cuenta como gasto: los consumos se contaron al comprar. */
    PAGO_TARJETA,
    /** Plata que pasa entre cuentas propias. No cuenta. */
    ENTRE_CUENTAS
}
