package ar.gastito.sueldo;

/** Cómo se calcula un concepto del sueldo. */
public enum TipoConceptoSueldo {
    /** Monto fijo del mes (básico, presentismo, adicionales). Suma al bruto. */
    REMUNERATIVO_FIJO("Remunerativo fijo"),
    /** Horas × valor de la hora normal × factor (o × el valor de esa hora, si la escala lo trae). Suma al bruto. */
    HORAS("Horas"),
    /** Monto fijo sin descuentos (viático, sumas extraordinarias). Se suma al neto. */
    NO_REMUNERATIVO("No remunerativo"),
    /** Porcentaje sobre el bruto remunerativo (jubilación, obra social, sindicato). Resta. */
    DESCUENTO_PORCENTAJE("Descuento");

    private final String nombre;

    TipoConceptoSueldo(String nombre) {
        this.nombre = nombre;
    }

    public String getNombre() {
        return nombre;
    }
}
