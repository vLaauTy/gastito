package ar.gastito.prueba;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import ar.gastito.ia.PropositoIa;

/**
 * Llamadas a la IA inventadas de los últimos meses, para ver la pantalla de costos con datos: texto, audio y tickets
 * casi todos los días, una consulta de vez en cuando, un recibo por mes y los resúmenes (el semanal el domingo a las
 * 21 y el mensual el día 1 a las 9). Con la misma semilla sale siempre lo mismo.
 */
final class PlanUsoIaPrueba {

    /** Se agrega al nombre del modelo para reconocer lo generado y borrarlo sin tocar lo gastado de verdad. */
    static final String MARCA = " (prueba)";

    /** Una llamada: cuántos tokens usó y cuándo. El costo lo calcula quien la guarda, con los precios de application.yml. */
    record Llamada(LocalDateTime fechaHora, PropositoIa proposito, long tokensEntrada, long tokensSalida) {
    }

    private final Random azar;

    private PlanUsoIaPrueba(long semilla) {
        this.azar = new Random(semilla);
    }

    /** Las llamadas desde el día 1 de hace {@code meses - 1} meses hasta {@code ahora}, de la más vieja a la más nueva. */
    static List<Llamada> armar(LocalDateTime ahora, int meses, long semilla) {
        return new PlanUsoIaPrueba(semilla).armar(ahora, meses);
    }

    private List<Llamada> armar(LocalDateTime ahora, int meses) {
        LocalDate desde = ahora.toLocalDate().withDayOfMonth(1).minusMonths(Math.max(meses, 1) - 1L);
        List<Llamada> llamadas = new ArrayList<>();
        for (LocalDate dia = desde; !dia.isAfter(ahora.toLocalDate()); dia = dia.plusDays(1)) {
            int cantidad = 2 + azar.nextInt(7);
            for (int i = 0; i < cantidad; i++) {
                llamadas.add(delDia(dia));
            }
            if (dia.getDayOfMonth() == 1) {
                llamadas.add(llamada(dia, LocalTime.of(9, 0), PropositoIa.RESUMEN, 2800, 4800, 450, 800));
            }
            if (dia.getDayOfMonth() == 4) {
                llamadas.add(llamada(dia, LocalTime.of(11, azar.nextInt(60)), PropositoIa.RECIBO, 2600, 3600, 700,
                        1100));
            }
            // La foto de la hoja de la escala del sindicato, cuando sale la paritaria.
            if (dia.getDayOfMonth() == 6) {
                llamadas.add(llamada(dia, LocalTime.of(10, azar.nextInt(60)), PropositoIa.ESCALA, 1800, 2600, 900,
                        1400));
            }
            if (dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
                llamadas.add(llamada(dia, LocalTime.of(21, 0), PropositoIa.RESUMEN, 2500, 4200, 400, 700));
            }
        }
        // Una consulta enorme por mes (muchas herramientas, mucho contexto): es la que conviene mirar para optimizar.
        for (LocalDate mes = desde; !mes.isAfter(ahora.toLocalDate()); mes = mes.plusMonths(1)) {
            LocalDate dia = mes.withDayOfMonth(12);
            if (!dia.isAfter(ahora.toLocalDate())) {
                llamadas.add(llamada(dia, LocalTime.of(20, 15), PropositoIa.CONSULTA, 14000, 19000, 500, 900));
            }
        }
        llamadas.removeIf(l -> l.fechaHora().isAfter(ahora));
        llamadas.sort(Comparator.comparing(Llamada::fechaHora));
        return List.copyOf(llamadas);
    }

    private Llamada delDia(LocalDate dia) {
        int sorteo = azar.nextInt(100);
        LocalTime hora = LocalTime.of(8 + azar.nextInt(15), azar.nextInt(60));
        if (sorteo < 45) {
            return llamada(dia, hora, PropositoIa.TEXTO, 800, 1100, 80, 160);
        } else if (sorteo < 55) {
            return llamada(dia, hora, PropositoIa.AUDIO, 850, 1150, 90, 170);
        } else if (sorteo < 85) {
            return llamada(dia, hora, PropositoIa.TICKET, 2200, 3600, 500, 1400);
        }
        return llamada(dia, hora, PropositoIa.CONSULTA, 3000, 5500, 150, 450);
    }

    private Llamada llamada(LocalDate dia, LocalTime hora, PropositoIa proposito, int entradaMinima,
            int entradaMaxima, int salidaMinima, int salidaMaxima) {
        return new Llamada(dia.atTime(hora), proposito, entre(entradaMinima, entradaMaxima),
                entre(salidaMinima, salidaMaxima));
    }

    private long entre(int minimo, int maximo) {
        return minimo + azar.nextInt(maximo - minimo + 1);
    }
}
