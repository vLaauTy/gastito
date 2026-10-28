package ar.gastito.recibo;

import ar.gastito.movimiento.ClaseIngreso;

/** Qué liquida el recibo. El aguinaldo va aparte del sueldo, igual que en los ingresos. */
public enum TipoRecibo {
    SUELDO_MENSUAL("Sueldo", ClaseIngreso.SUELDO),
    AGUINALDO("Aguinaldo", ClaseIngreso.AGUINALDO),
    VACACIONES("Vacaciones", ClaseIngreso.SUELDO),
    LIQUIDACION_FINAL("Liquidación final", ClaseIngreso.SUELDO);

    private final String nombre;
    private final ClaseIngreso clase;

    TipoRecibo(String nombre, ClaseIngreso clase) {
        this.nombre = nombre;
        this.clase = clase;
    }

    public String getNombre() {
        return nombre;
    }

    /** La clase del ingreso que se crea por el neto. */
    public ClaseIngreso claseIngreso() {
        return clase;
    }

    /** Solo el sueldo mensual y el aguinaldo compiten con lo que se cargó a mano con /sueldo y /aguinaldo. */
    public boolean reemplazaCargaManual() {
        return this == SUELDO_MENSUAL || this == AGUINALDO;
    }
}
