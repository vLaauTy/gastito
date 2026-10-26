package ar.gastito.juntada;

/** Si alguien de la juntada ya me pagó su parte. */
public enum EstadoParticipante {
    PENDIENTE,
    PAGADO,
    /** No me la va a pagar: su parte pasó a ser gasto mío. */
    PERDIDO
}
