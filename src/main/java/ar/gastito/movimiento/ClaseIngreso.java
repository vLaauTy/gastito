package ar.gastito.movimiento;

/** Qué tipo de ingreso es. Una venta, un regalo o un trabajo particular van como EXTRA, con descripción. */
public enum ClaseIngreso {
    SUELDO("Sueldo"),
    /** Se cobra dos veces por año: va aparte para no inflar la comparación del sueldo entre meses. */
    AGUINALDO("Aguinaldo"),
    EXTRA("Extra");

    private final String nombre;

    ClaseIngreso(String nombre) {
        this.nombre = nombre;
    }

    public String getNombre() {
        return nombre;
    }
}
