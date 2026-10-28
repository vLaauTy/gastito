package ar.gastito.ia;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.movimiento.Periodo;

/**
 * Todos los números de la pantalla de costos de la IA (dashboard y /costos), en dólares. Salen de uso_ia, donde cada
 * llamada quedó con el costo calculado con los precios de ese momento: si los precios cambian, el historial no se
 * recalcula. Los importes llevan 6 decimales: una llamada a Haiku cuesta fracciones de centavo.
 */
@Service
public class CostosIaService {

    static final int ESCALA = 6;
    static final int HISTORIAL_MESES = 6;
    static final int LLAMADAS_MAS_CARAS = 10;

    private final UsoIaRepository usos;
    private final TopeIaService tope;
    private final Clock clock;

    public CostosIaService(UsoIaRepository usos, TopeIaService tope, Clock clock) {
        this.usos = usos;
        this.tope = tope;
        this.clock = clock;
    }

    public record Total(YearMonth mes, long llamadas, long tokensEntrada, long tokensSalida, BigDecimal costoUsd) {
    }

    /** @param costoPromedioUsd lo que costó cada llamada en promedio; 0 si no hubo */
    public record PorProposito(PropositoIa proposito, long llamadas, long tokensEntrada, long tokensSalida,
            BigDecimal costoUsd, BigDecimal costoPromedioUsd) {
    }

    /** @param porcentajeDelMaximo el ancho de la barra: el mes más caro de los seis es 100 */
    public record MesHistorial(YearMonth mes, long llamadas, BigDecimal costoUsd, BigDecimal porcentajeDelMaximo) {
    }

    /** @param futuro un día que todavía no llegó */
    public record Dia(int dia, LocalDate fecha, BigDecimal costoUsd, boolean futuro) {
    }

    public record LlamadaCara(Long id, LocalDateTime fechaHora, PropositoIa proposito, long tokensEntrada,
            long tokensSalida, BigDecimal costoUsd) {
    }

    /**
     * Lo gastado hasta ahora llevado a fin de mes: lo gastado ÷ días transcurridos × días del mes. En un mes que ya
     * terminó es lo gastado; en uno que no empezó, cero.
     *
     * @param diasTranscurridos los días del mes que ya pasaron, hoy incluido
     */
    public record Proyeccion(BigDecimal costoUsd, int diasTranscurridos, int diasDelMes) {
    }

    /**
     * @param topeUsd    el tope mensual; null si los avisos están apagados
     * @param porcentaje lo gastado sobre el tope, entero hacia abajo; null sin tope
     */
    public record Tope(BigDecimal topeUsd, Integer porcentaje) {
    }

    /**
     * @param actual   el mes que se está viendo
     * @param anterior el mes de antes
     * @param porDia   un elemento por día del mes
     * @param historial los últimos {@value #HISTORIAL_MESES} meses hasta el que se está viendo, del más viejo al más
     *                 nuevo
     */
    public record Panel(YearMonth mes, boolean mesActual, Total actual, Total anterior, Proyeccion proyeccion,
            Tope tope, List<PorProposito> porProposito, List<Dia> porDia, List<MesHistorial> historial,
            List<LlamadaCara> masCaras) {
    }

    @Transactional(readOnly = true)
    public Panel panel(YearMonth mes) {
        LocalDate hoy = LocalDate.now(clock);
        YearMonth actualMes = YearMonth.from(hoy);
        Total actual = total(mes);
        List<Total> historial = new ArrayList<>();
        for (int i = HISTORIAL_MESES - 1; i >= 0; i--) {
            historial.add(i == 0 ? actual : total(mes.minusMonths(i)));
        }
        Total anterior = historial.get(HISTORIAL_MESES - 2);
        Periodo periodo = Periodo.delMes(mes);
        return new Panel(mes, mes.equals(actualMes), actual, anterior, proyeccion(actual.costoUsd(), mes, hoy),
                new Tope(tope.topeUsd().orElse(null), tope.porcentajeDelTope(actual.costoUsd()).orElse(null)),
                porProposito(periodo), porDia(mes, hoy, periodo), historial(historial), masCaras(periodo));
    }

    private static List<MesHistorial> historial(List<Total> meses) {
        BigDecimal maximo = meses.stream().map(Total::costoUsd).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return meses.stream().map(t -> new MesHistorial(t.mes(), t.llamadas(), t.costoUsd(),
                maximo.signum() == 0 ? BigDecimal.ZERO.setScale(1)
                        : t.costoUsd().multiply(BigDecimal.valueOf(100)).divide(maximo, 1, RoundingMode.HALF_UP)))
                .toList();
    }

    /** Lo gastado en el mes de hoy, para el aviso del tope y para /costos. */
    @Transactional(readOnly = true)
    public Total delMesActual() {
        return total(YearMonth.now(clock));
    }

    private Total total(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        ResumenUsoIa r = usos.resumen(periodo.desde(), periodo.hasta());
        return new Total(mes, r.llamadas(), r.tokensEntrada(), r.tokensSalida(), redondear(r.costoUsd()));
    }

    /** Lo gastado ÷ días transcurridos × días del mes. */
    static Proyeccion proyeccion(BigDecimal gastado, YearMonth mes, LocalDate hoy) {
        YearMonth actual = YearMonth.from(hoy);
        int diasDelMes = mes.lengthOfMonth();
        int transcurridos = mes.equals(actual) ? hoy.getDayOfMonth() : mes.isBefore(actual) ? diasDelMes : 0;
        BigDecimal costo = transcurridos == 0 ? redondear(BigDecimal.ZERO)
                : gastado.multiply(BigDecimal.valueOf(diasDelMes))
                        .divide(BigDecimal.valueOf(transcurridos), ESCALA, RoundingMode.HALF_UP);
        return new Proyeccion(costo, transcurridos, diasDelMes);
    }

    /** Siempre los seis propósitos, aunque no hayan tenido llamadas. */
    private List<PorProposito> porProposito(Periodo periodo) {
        Map<PropositoIa, UsoIaRepository.UsoDetallado> usados = new EnumMap<>(PropositoIa.class);
        usos.detalladoPorProposito(periodo.desde(), periodo.hasta()).forEach(u -> usados.put(u.proposito(), u));
        List<PorProposito> lista = new ArrayList<>();
        for (PropositoIa proposito : PropositoIa.values()) {
            UsoIaRepository.UsoDetallado u = usados.get(proposito);
            if (u == null) {
                lista.add(new PorProposito(proposito, 0, 0, 0, redondear(BigDecimal.ZERO), redondear(BigDecimal.ZERO)));
            } else {
                lista.add(new PorProposito(proposito, u.llamadas(), u.tokensEntrada(), u.tokensSalida(),
                        redondear(u.costoUsd()), promedio(u.costoUsd(), u.llamadas())));
            }
        }
        return List.copyOf(lista);
    }

    private List<Dia> porDia(YearMonth mes, LocalDate hoy, Periodo periodo) {
        BigDecimal[] porDia = new BigDecimal[mes.lengthOfMonth() + 1];
        for (UsoIa u : usos.findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByFechaHoraAscIdAsc(
                periodo.desde(), periodo.hasta())) {
            int dia = u.getFechaHora().getDayOfMonth();
            porDia[dia] = porDia[dia] == null ? u.getCostoUsd() : porDia[dia].add(u.getCostoUsd());
        }
        List<Dia> dias = new ArrayList<>();
        for (int dia = 1; dia <= mes.lengthOfMonth(); dia++) {
            LocalDate fecha = mes.atDay(dia);
            dias.add(new Dia(dia, fecha, redondear(porDia[dia] == null ? BigDecimal.ZERO : porDia[dia]),
                    fecha.isAfter(hoy)));
        }
        return List.copyOf(dias);
    }

    private List<LlamadaCara> masCaras(Periodo periodo) {
        return usos.findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByCostoUsdDescIdAsc(periodo.desde(),
                periodo.hasta(), PageRequest.of(0, LLAMADAS_MAS_CARAS)).stream()
                .map(u -> new LlamadaCara(u.getId(), u.getFechaHora(), u.getProposito(), u.getTokensEntrada(),
                        u.getTokensSalida(), redondear(u.getCostoUsd())))
                .toList();
    }

    private static BigDecimal promedio(BigDecimal costo, long llamadas) {
        return llamadas == 0 ? redondear(BigDecimal.ZERO)
                : costo.divide(BigDecimal.valueOf(llamadas), ESCALA, RoundingMode.HALF_UP);
    }

    private static BigDecimal redondear(BigDecimal monto) {
        return monto.setScale(ESCALA, RoundingMode.HALF_UP);
    }
}
