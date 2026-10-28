package ar.gastito.recibo;

/** En qué parte del recibo está un concepto. */
public enum GrupoConcepto {
    REMUNERATIVO("Remunerativo"),
    NO_REMUNERATIVO("No remunerativo"),
    DESCUENTO("Descuento");

    private final String nombre;

    GrupoConcepto(String nombre) {
        this.nombre = nombre;
    }

    public String getNombre() {
        return nombre;
    }
}
