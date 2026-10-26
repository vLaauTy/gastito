package ar.gastito.fijo;

/** Cada cuánto se cobra un gasto fijo. */
public enum Frecuencia {
    MENSUAL(1),
    /** Cada dos meses, desde el mes de inicio (la luz o el gas, en algunas zonas). */
    BIMESTRAL(2);

    private final int meses;

    Frecuencia(int meses) {
        this.meses = meses;
    }

    public int getMeses() {
        return meses;
    }
}
