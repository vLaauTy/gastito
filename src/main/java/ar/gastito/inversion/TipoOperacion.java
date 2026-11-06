package ar.gastito.inversion;

/** Lo que se puso en una inversión que no es un movimiento (no pasa plata desde otra cuenta). */
public enum TipoOperacion {
    /** El ahorro previo: la plata que ya estaba invertida al empezar a usar Gastito. No es un ingreso. */
    SALDO_INICIAL,
    /** De la bolsa de la cuenta a una posición ("compré 100.000 de CEDEAR SPY"). */
    COMPRA,
    /** De una posición a la bolsa ("vendí 50.000 de SPY", "rescaté 100.000 del FCI"). */
    VENTA
}
