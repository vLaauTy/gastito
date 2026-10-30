package ar.gastito.sueldo.escala;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * Lo que se leyó de la hoja (de un Excel o de una foto), todavía sin interpretar: una columna por mes con sus filas
 * tal cual. Qué fila es qué concepto lo decide {@link ImportacionEscala} con los alias.
 *
 * @param columnas en el orden de la hoja
 */
public record HojaEscala(List<Columna> columnas) {

    public HojaEscala {
        columnas = List.copyOf(columnas);
    }

    /** @param mes el mes trabajado de la columna; null si la planilla de un solo mes no lo dice */
    public record Columna(YearMonth mes, List<Fila> filas) {

        public Columna {
            filas = List.copyOf(filas);
        }

        public Columna conMes(YearMonth otro) {
            return new Columna(otro, filas);
        }
    }

    /**
     * @param nombre el nombre de la fila como está en la hoja
     * @param texto  el valor como estaba escrito (para avisar si no se entendió)
     * @param valor  el importe; null si el texto no tiene formato de plata
     */
    public record Fila(String nombre, String texto, BigDecimal valor) {

        public static Fila de(String nombre, String texto) {
            return new Fila(nombre, texto, ImporteHoja.leer(texto).orElse(null));
        }
    }

    /** Si alguna columna no dice de qué mes es (la plantilla de dos columnas sin la fila "Mes"). */
    public boolean faltaElMes() {
        return columnas.stream().anyMatch(c -> c.mes() == null);
    }

    /** La misma hoja con el mes puesto en las columnas que no lo tenían. */
    public HojaEscala conMes(YearMonth mes) {
        return new HojaEscala(columnas.stream().map(c -> c.mes() == null ? c.conMes(mes) : c).toList());
    }
}
