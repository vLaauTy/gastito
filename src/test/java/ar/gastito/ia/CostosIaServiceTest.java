package ar.gastito.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CostosIaServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Jueves 15/10/2026 a las 10:00: el día 15 de un mes de 31. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 15, 10, 0);
    static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    @Autowired
    UsoIaRepository usos;

    @Autowired
    AvisoTopeIaRepository avisos;

    CostosIaService costos;

    @BeforeEach
    void setUp() {
        Clock reloj = Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA);
        costos = new CostosIaService(usos, new TopeIaService(usos, avisos, new TopeIaProperties(new BigDecimal("2")),
                reloj), reloj);
    }

    private void llamada(LocalDateTime fechaHora, PropositoIa proposito, long entrada, long salida, String costo) {
        usos.save(new UsoIa(fechaHora, "claude-haiku-5-5", entrada, salida, proposito, new BigDecimal(costo)));
    }

    /** Octubre: 6 llamadas por USD 0,0049. Septiembre: 2 por 0,003. Agosto: 1. Junio: 1. */
    private void cargarDatos() {
        llamada(LocalDateTime.of(2026, 10, 1, 9, 0), PropositoIa.TEXTO, 900, 100, "0.0002");
        llamada(LocalDateTime.of(2026, 10, 1, 18, 30), PropositoIa.AUDIO, 1000, 120, "0.0003");
        llamada(LocalDateTime.of(2026, 10, 2, 12, 0), PropositoIa.TICKET, 2500, 700, "0.0006");
        llamada(LocalDateTime.of(2026, 10, 2, 13, 0), PropositoIa.TICKET, 3200, 900, "0.0008");
        llamada(LocalDateTime.of(2026, 10, 15, 9, 0), PropositoIa.CONSULTA, 4000, 400, "0.002");
        llamada(LocalDateTime.of(2026, 10, 15, 9, 30), PropositoIa.RECIBO, 3000, 900, "0.001");
        llamada(LocalDateTime.of(2026, 9, 10, 9, 0), PropositoIa.TEXTO, 1, 1, "0.001");
        llamada(LocalDateTime.of(2026, 9, 30, 23, 59), PropositoIa.RESUMEN, 1, 1, "0.002");
        llamada(LocalDateTime.of(2026, 8, 3, 9, 0), PropositoIa.TEXTO, 1, 1, "0.0005");
        llamada(LocalDateTime.of(2026, 6, 20, 9, 0), PropositoIa.TEXTO, 1, 1, "0.0001");
    }

    // --- Totales ---

    @Test
    void totalDelMesYDelAnterior() {
        cargarDatos();

        CostosIaService.Panel p = costos.panel(OCTUBRE);

        assertThat(p.mesActual()).isTrue();
        assertThat(p.actual().llamadas()).isEqualTo(6);
        assertThat(p.actual().costoUsd()).isEqualByComparingTo("0.0049");
        assertThat(p.actual().tokensEntrada()).isEqualTo(14600);
        assertThat(p.actual().tokensSalida()).isEqualTo(3120);
        assertThat(p.anterior().mes()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(p.anterior().llamadas()).isEqualTo(2);
        assertThat(p.anterior().costoUsd()).isEqualByComparingTo("0.003");
    }

    @Test
    void unaLlamadaDeUnMesNoEntraEnOtro() {
        cargarDatos();

        // La del 30/9 a las 23:59 es de septiembre; la del 1/10 a las 9:00, de octubre.
        assertThat(costos.panel(YearMonth.of(2026, 9)).actual().llamadas()).isEqualTo(2);
        assertThat(costos.panel(OCTUBRE).actual().llamadas()).isEqualTo(6);
    }

    @Test
    void sinNadaTodoEsCero() {
        CostosIaService.Panel p = costos.panel(OCTUBRE);

        assertThat(p.actual().llamadas()).isZero();
        assertThat(p.actual().costoUsd()).isEqualByComparingTo("0");
        assertThat(p.proyeccion().costoUsd()).isEqualByComparingTo("0");
        assertThat(p.masCaras()).isEmpty();
        assertThat(p.porProposito()).hasSize(PropositoIa.values().length).allSatisfy(x -> assertThat(x.llamadas()).isZero());
        assertThat(p.historial()).hasSize(6).allSatisfy(m -> assertThat(m.porcentajeDelMaximo()).isEqualByComparingTo("0"));
    }

    // --- Desglose por propósito ---

    @Test
    void desglosePorPropositoConTokensCostoYPromedioPorLlamada() {
        cargarDatos();

        var porProposito = costos.panel(OCTUBRE).porProposito();

        assertThat(porProposito).extracting(CostosIaService.PorProposito::proposito).containsExactly(
                PropositoIa.TEXTO, PropositoIa.AUDIO, PropositoIa.TICKET, PropositoIa.RECIBO, PropositoIa.RESUMEN,
                PropositoIa.CONSULTA, PropositoIa.ESCALA);
        CostosIaService.PorProposito ticket = porProposito.get(2);
        assertThat(ticket.llamadas()).isEqualTo(2);
        assertThat(ticket.tokensEntrada()).isEqualTo(5700);
        assertThat(ticket.tokensSalida()).isEqualTo(1600);
        assertThat(ticket.costoUsd()).isEqualByComparingTo("0.0014");
        assertThat(ticket.costoPromedioUsd()).isEqualByComparingTo("0.0007");
        CostosIaService.PorProposito resumen = porProposito.get(4);
        assertThat(resumen.llamadas()).isZero();
        assertThat(resumen.costoUsd()).isEqualByComparingTo("0");
        assertThat(resumen.costoPromedioUsd()).isEqualByComparingTo("0");
    }

    @Test
    void elPromedioPorLlamadaConservaLasFraccionesDeCentavo() {
        llamada(LocalDateTime.of(2026, 10, 3, 9, 0), PropositoIa.TEXTO, 1, 1, "0.00000100");
        llamada(LocalDateTime.of(2026, 10, 3, 9, 1), PropositoIa.TEXTO, 1, 1, "0.00000201");

        var texto = costos.panel(OCTUBRE).porProposito().get(0);

        assertThat(texto.costoUsd()).isEqualByComparingTo("0.000003");
        assertThat(texto.costoPromedioUsd()).isEqualByComparingTo("0.000002");
    }

    @Test
    void elCostoGuardadoNoSeRecalculaSiCambianLosPrecios() {
        // 1.000 tokens de entrada a los precios de hoy costarían 0,0001; esta llamada se guardó con otro precio.
        llamada(LocalDateTime.of(2026, 10, 3, 9, 0), PropositoIa.TEXTO, 1000, 0, "0.5");

        assertThat(costos.panel(OCTUBRE).actual().costoUsd()).isEqualByComparingTo("0.5");
    }

    // --- Proyección ---

    @Test
    void proyeccionEnLaMitadDelMes() {
        cargarDatos();

        CostosIaService.Proyeccion p = costos.panel(OCTUBRE).proyeccion();

        // 0,0049 gastado en 15 días de 31: 0,0049 / 15 * 31.
        assertThat(p.diasTranscurridos()).isEqualTo(15);
        assertThat(p.diasDelMes()).isEqualTo(31);
        assertThat(p.costoUsd()).isEqualByComparingTo("0.010127");
    }

    @Test
    void proyeccionElPrimerDiaDelMes() {
        CostosIaService.Proyeccion p = CostosIaService.proyeccion(new BigDecimal("0.10"), OCTUBRE,
                LocalDate.of(2026, 10, 1));

        assertThat(p.diasTranscurridos()).isEqualTo(1);
        assertThat(p.costoUsd()).isEqualByComparingTo("3.10");
    }

    @Test
    void proyeccionElUltimoDiaEsLoGastado() {
        CostosIaService.Proyeccion p = CostosIaService.proyeccion(new BigDecimal("1.62"), OCTUBRE,
                LocalDate.of(2026, 10, 31));

        assertThat(p.diasTranscurridos()).isEqualTo(31);
        assertThat(p.costoUsd()).isEqualByComparingTo("1.62");
    }

    @Test
    void proyeccionDeUnMesQueTerminoEsLoGastadoYDeUnoFuturoEsCero() {
        LocalDate hoy = LocalDate.of(2026, 10, 15);

        assertThat(CostosIaService.proyeccion(new BigDecimal("0.30"), YearMonth.of(2026, 9), hoy).costoUsd())
                .isEqualByComparingTo("0.30");
        CostosIaService.Proyeccion futuro = CostosIaService.proyeccion(BigDecimal.ZERO, YearMonth.of(2026, 11), hoy);
        assertThat(futuro.diasTranscurridos()).isZero();
        assertThat(futuro.costoUsd()).isEqualByComparingTo("0");
    }

    @Test
    void laProyeccionUsaLosDiasDeFebrero() {
        CostosIaService.Proyeccion p = CostosIaService.proyeccion(new BigDecimal("1.00"), YearMonth.of(2026, 2),
                LocalDate.of(2026, 2, 14));

        assertThat(p.diasDelMes()).isEqualTo(28);
        assertThat(p.costoUsd()).isEqualByComparingTo("2.00");
    }

    // --- Por día ---

    @Test
    void costoPorDiaConLosDiasQueFaltanMarcadosComoFuturos() {
        cargarDatos();

        var dias = costos.panel(OCTUBRE).porDia();

        assertThat(dias).hasSize(31);
        assertThat(dias.get(0).costoUsd()).isEqualByComparingTo("0.0005");
        assertThat(dias.get(1).costoUsd()).isEqualByComparingTo("0.0014");
        assertThat(dias.get(2).costoUsd()).isEqualByComparingTo("0");
        assertThat(dias.get(14).costoUsd()).isEqualByComparingTo("0.003");
        assertThat(dias.get(14).futuro()).isFalse();
        assertThat(dias.get(15).futuro()).isTrue();
        assertThat(dias.get(30).fecha()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    // --- Historial ---

    @Test
    void historialDeSeisMesesConLaBarraRelativaAlMasCaro() {
        cargarDatos();

        var historial = costos.panel(OCTUBRE).historial();

        assertThat(historial).extracting(CostosIaService.MesHistorial::mes).containsExactly(
                YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8),
                YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(historial.get(0).costoUsd()).isEqualByComparingTo("0");
        assertThat(historial.get(1).costoUsd()).isEqualByComparingTo("0.0001");
        assertThat(historial.get(4).porcentajeDelMaximo()).isEqualByComparingTo("61.2");
        assertThat(historial.get(5).porcentajeDelMaximo()).isEqualByComparingTo("100");
        assertThat(historial.get(4).llamadas()).isEqualTo(2);
    }

    @Test
    void elHistorialDeUnMesAnteriorTerminaEnEseMes() {
        cargarDatos();

        var historial = costos.panel(YearMonth.of(2026, 9)).historial();

        assertThat(historial.get(5).mes()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(historial.get(5).porcentajeDelMaximo()).isEqualByComparingTo("100");
        assertThat(costos.panel(YearMonth.of(2026, 9)).mesActual()).isFalse();
    }

    // --- Las más caras ---

    @Test
    void lasMasCarasDelMesDeMayorAMenor() {
        cargarDatos();

        var caras = costos.panel(OCTUBRE).masCaras();

        assertThat(caras).extracting(CostosIaService.LlamadaCara::proposito).containsExactly(PropositoIa.CONSULTA,
                PropositoIa.RECIBO, PropositoIa.TICKET, PropositoIa.TICKET, PropositoIa.AUDIO, PropositoIa.TEXTO);
        assertThat(caras.get(0).costoUsd()).isEqualByComparingTo("0.002");
        assertThat(caras.get(0).fechaHora()).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 0));
        assertThat(caras.get(0).tokensEntrada()).isEqualTo(4000);
        assertThat(caras.get(0).tokensSalida()).isEqualTo(400);
    }

    @Test
    void lasMasCarasSonComoMuchoDiez() {
        for (int i = 1; i <= 12; i++) {
            llamada(LocalDateTime.of(2026, 10, i, 9, 0), PropositoIa.TEXTO, 1, 1, "0.00" + (10 + i));
        }

        var caras = costos.panel(OCTUBRE).masCaras();

        assertThat(caras).hasSize(10);
        assertThat(caras.get(0).costoUsd()).isEqualByComparingTo("0.0022");
        assertThat(caras.get(9).costoUsd()).isEqualByComparingTo("0.0013");
    }

    // --- Tope ---

    @Test
    void elPanelDiceQueParteDelTopeLlevaGastada() {
        llamada(LocalDateTime.of(2026, 10, 3, 9, 0), PropositoIa.CONSULTA, 1, 1, "1.62");

        CostosIaService.Tope tope = costos.panel(OCTUBRE).tope();

        assertThat(tope.topeUsd()).isEqualByComparingTo("2");
        assertThat(tope.porcentaje()).isEqualTo(81);
    }
}
