package ar.gastito.sueldo.escala;

/** Qué es una fila de la hoja de la escala. */
public enum DestinoAlias {
    /** El monto (o el valor de la hora) de un concepto del sueldo. */
    CONCEPTO,
    /** El valor de la hora normal de la escala. */
    HORA_NORMAL,
    /** "Valores netos": no se guarda, se usa para controlar los porcentajes de descuento. */
    VALORES_NETOS
}
