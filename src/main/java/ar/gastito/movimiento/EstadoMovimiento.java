package ar.gastito.movimiento;

public enum EstadoMovimiento {
    /** Interpretado, esperando que el usuario confirme. No cuenta en ningún total. */
    PENDIENTE,
    CONFIRMADO,
    CANCELADO
}
