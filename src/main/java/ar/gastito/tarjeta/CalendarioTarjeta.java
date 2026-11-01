package ar.gastito.tarjeta;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Los cierres y vencimientos de la tarjeta, mes por mes. Hay un resumen por mes (su {@code periodo}: el mes del
 * cierre sin correrlo por fin de semana). Los que el usuario cargó tienen sus fechas; los demás se estiman a partir
 * del confirmado más cercano (el último anterior o, si no hay, el primero posterior): el mismo día del mes, corrido
 * al lunes si cae en fin de semana, y la misma distancia entre cierre y vencimiento. Estimar siempre desde un
 * confirmado (y no desde otro estimado) evita que el día se corra mes a mes.
 * <p>
 * El período de un resumen va desde el día siguiente al cierre anterior hasta su cierre, los dos incluidos.
 */
public final class CalendarioTarjeta {

    /** Las fechas de un resumen. */
    public record Fechas(YearMonth periodo, LocalDate cierre, LocalDate vencimiento, boolean confirmadas) {

        public boolean estimadas() {
            return !confirmadas;
        }
    }

    private final List<Fechas> confirmadas;

    /** @param guardados los resúmenes guardados; solo cuentan los que tienen las fechas confirmadas */
    public CalendarioTarjeta(List<Fechas> guardados) {
        this.confirmadas = guardados.stream().filter(Fechas::confirmadas)
                .sorted(Comparator.comparing(Fechas::periodo)).toList();
    }

    /** Sin ningún resumen confirmado no hay de dónde estimar: el ciclo de la tarjeta todavía no se configuró. */
    public boolean isVacio() {
        return confirmadas.isEmpty();
    }

    /** Las fechas del resumen de ese mes: las cargadas o, si no, las estimadas. */
    public Fechas fechas(YearMonth periodo) {
        Optional<Fechas> propia = confirmadas.stream().filter(f -> f.periodo().equals(periodo)).findFirst();
        if (propia.isPresent()) {
            return propia.get();
        }
        Fechas ancla = ancla(periodo);
        LocalDate cierre = habil(periodo.atDay(Math.min(diaDeCierre(ancla), periodo.lengthOfMonth())));
        long distancia = ChronoUnit.DAYS.between(ancla.cierre(), ancla.vencimiento());
        return new Fechas(periodo, cierre, habil(cierre.plusDays(distancia)), false);
    }

    /** El primer día del período del resumen (el siguiente al cierre anterior). */
    public LocalDate desde(YearMonth periodo) {
        return fechas(periodo.minusMonths(1)).cierre().plusDays(1);
    }

    /** El resumen en el que entra lo comprado ese día: después del cierre anterior y hasta su cierre. */
    public YearMonth periodoDe(LocalDate dia) {
        YearMonth periodo = YearMonth.from(dia);
        while (dia.isAfter(fechas(periodo).cierre())) {
            periodo = periodo.plusMonths(1);
        }
        while (!dia.isAfter(fechas(periodo.minusMonths(1)).cierre())) {
            periodo = periodo.minusMonths(1);
        }
        return periodo;
    }

    /**
     * A qué resumen se refieren unas fechas que cargó el usuario: el del mes del cierre o el anterior (si el cierre
     * se corrió al mes siguiente), el que tenga el cierre más cerca.
     */
    public YearMonth periodoParaCierre(LocalDate cierre) {
        YearMonth mes = YearMonth.from(cierre);
        if (isVacio()) {
            return mes;
        }
        YearMonth anterior = mes.minusMonths(1);
        long aEste = Math.abs(ChronoUnit.DAYS.between(fechas(mes).cierre(), cierre));
        long aAnterior = Math.abs(ChronoUnit.DAYS.between(fechas(anterior).cierre(), cierre));
        return aAnterior < aEste ? anterior : mes;
    }

    /** Sábado o domingo pasa al lunes. */
    static LocalDate habil(LocalDate dia) {
        return switch (dia.getDayOfWeek()) {
            case SATURDAY -> dia.plusDays(2);
            case SUNDAY -> dia.plusDays(1);
            default -> dia;
        };
    }

    private Fechas ancla(YearMonth periodo) {
        if (isVacio()) {
            throw new IllegalStateException("Sin un resumen con fechas confirmadas no se puede estimar");
        }
        Fechas ancla = confirmadas.get(0);
        for (Fechas f : confirmadas) {
            if (!f.periodo().isAfter(periodo)) {
                ancla = f;
            }
        }
        return ancla;
    }

    /**
     * El día del mes en que cierra: el del cierre confirmado o, si ese cierre se corrió al mes siguiente (cerraba a
     * fin de mes y cayó en fin de semana), el último día de su mes.
     */
    private static int diaDeCierre(Fechas ancla) {
        return YearMonth.from(ancla.cierre()).equals(ancla.periodo()) ? ancla.cierre().getDayOfMonth()
                : ancla.periodo().lengthOfMonth();
    }
}
