package ar.gastito.prestamo;

public enum EstadoCuota {
    PENDIENTE,
    COBRADA,
    /** Pasaron unos días de su fecha y no me la pagó (se puede cobrar igual). */
    ATRASADA;

    public boolean porCobrar() {
        return this != COBRADA;
    }
}
