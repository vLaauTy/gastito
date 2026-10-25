package ar.gastito.resumen;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * El período de un resumen, con los dos días incluidos: una semana de lunes a domingo o un mes calendario. Todas
 * las fechas están en la zona del usuario (el Clock de la aplicación).
 */
public record PeriodoResumen(TipoResumen tipo, LocalDate desde, LocalDate hasta) {

    public PeriodoResumen {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("El período empieza después de terminar: " + desde + " a " + hasta);
        }
    }

    /** La semana (lunes a domingo) que contiene el día. */
    public static PeriodoResumen semanaDe(LocalDate dia) {
        LocalDate lunes = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new PeriodoResumen(TipoResumen.SEMANAL, lunes, lunes.plusDays(6));
    }

    public static PeriodoResumen mesDe(LocalDate dia) {
        YearMonth mes = YearMonth.from(dia);
        return new PeriodoResumen(TipoResumen.MENSUAL, mes.atDay(1), mes.atEndOfMonth());
    }

    public static PeriodoResumen de(TipoResumen tipo, LocalDate dia) {
        return tipo == TipoResumen.SEMANAL ? semanaDe(dia) : mesDe(dia);
    }

    public PeriodoResumen anterior() {
        return de(tipo, desde.minusDays(1));
    }

    public PeriodoResumen siguiente() {
        return de(tipo, hasta.plusDays(1));
    }

    /**
     * Cuándo se manda el resumen automático: la semana, el domingo que la cierra a la hora semanal; el mes, el
     * día 1 del mes siguiente a la hora mensual.
     */
    public LocalDateTime momentoDeEnvio(LocalTime horaSemanal, LocalTime horaMensual) {
        return tipo == TipoResumen.SEMANAL ? hasta.atTime(horaSemanal) : hasta.plusDays(1).atTime(horaMensual);
    }

    /** El período más reciente cuyo momento de envío ya pasó. */
    public static PeriodoResumen ultimoVencido(TipoResumen tipo, LocalDateTime ahora, LocalTime horaSemanal,
            LocalTime horaMensual) {
        // El mensual sale después de que termina el mes: el día 1 antes de la hora, hay que ir dos meses atrás.
        PeriodoResumen periodo = de(tipo, ahora.toLocalDate());
        while (periodo.momentoDeEnvio(horaSemanal, horaMensual).isAfter(ahora)) {
            periodo = periodo.anterior();
        }
        return periodo;
    }

    /** Si el día está dentro del período. */
    public boolean contiene(LocalDate dia) {
        return !dia.isBefore(desde) && !dia.isAfter(hasta);
    }

    /**
     * Hasta qué día van los datos si se pide el resumen el día {@code hoy}: el período entero si ya terminó, o
     * hasta hoy si está en curso (resumen parcial).
     */
    public LocalDate hastaParaDatos(LocalDate hoy) {
        return hoy.isBefore(hasta) ? hoy : hasta;
    }

    /** Cuántos días van desde el inicio hasta {@code ultimo}, incluidos los dos. */
    public long diasHasta(LocalDate ultimo) {
        return ChronoUnit.DAYS.between(desde, ultimo) + 1;
    }

    /**
     * El período anterior "hasta el mismo punto": la misma cantidad de días desde el inicio, sin pasarse de su fin
     * (el 31 de un mes se compara con el 30 de un mes de 30 días).
     */
    public LocalDate mismoPuntoEnAnterior(LocalDate ultimo) {
        PeriodoResumen anterior = anterior();
        LocalDate mismoPunto = anterior.desde().plusDays(diasHasta(ultimo) - 1);
        return mismoPunto.isAfter(anterior.hasta()) ? anterior.hasta() : mismoPunto;
    }

    /** "SEMANAL 2026-10-13", para los logs. */
    @Override
    public String toString() {
        return tipo + " " + desde + " a " + hasta;
    }
}
