package ar.gastito.inversion;

/** De dónde salió una valuación. */
public enum OrigenValuacion {
    /** El usuario dijo cuánto vale ("Cocos vale 870.000"). */
    MANUAL,
    /** "Igual que antes": el mismo valor, con la fecha de hoy. */
    IGUAL,
    /** El valor anterior más o menos lo que movió un aporte, un retiro, una compra o una venta. */
    AJUSTE;

    /** Si dice cuánto vale de verdad (para "actualizado hace N días"): un ajuste solo arrastra el valor anterior. */
    public boolean esActualizacion() {
        return this != AJUSTE;
    }
}
