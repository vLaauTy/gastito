package ar.gastito.ia;

/** La IA no se pudo usar: falta la API key, error de red o de la API, o respuesta incompleta. */
public class IaNoDisponibleException extends RuntimeException {

    public IaNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
