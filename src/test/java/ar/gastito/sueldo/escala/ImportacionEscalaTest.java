package ar.gastito.sueldo.escala;

import static ar.gastito.sueldo.escala.EscalaDePrueba.AIS;
import static ar.gastito.sueldo.escala.EscalaDePrueba.AL_100;
import static ar.gastito.sueldo.escala.EscalaDePrueba.AL_50;
import static ar.gastito.sueldo.escala.EscalaDePrueba.BASICO;
import static ar.gastito.sueldo.escala.EscalaDePrueba.CCT;
import static ar.gastito.sueldo.escala.EscalaDePrueba.CONCEPTOS;
import static ar.gastito.sueldo.escala.EscalaDePrueba.EXTRAORDINARIA;
import static ar.gastito.sueldo.escala.EscalaDePrueba.JUBILACION;
import static ar.gastito.sueldo.escala.EscalaDePrueba.NOCTURNAS;
import static ar.gastito.sueldo.escala.EscalaDePrueba.NOVIEMBRE;
import static ar.gastito.sueldo.escala.EscalaDePrueba.OCTUBRE;
import static ar.gastito.sueldo.escala.EscalaDePrueba.VIATICO;
import static ar.gastito.sueldo.escala.EscalaDePrueba.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.escala.ImportacionEscala.MesPropuesto;
import ar.gastito.sueldo.escala.ImportacionEscala.Valor;

class ImportacionEscalaTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    private final EscalaDePrueba.Repos repos = EscalaDePrueba.repos();
    private final ImportacionEscala importacion = repos.importacion();

    private static Valor leido(MesPropuesto m, Long conceptoId) {
        return m.leidos().stream().filter(v -> conceptoId.equals(v.conceptoId())).findFirst().orElse(null);
    }

    @Test
    void cadaColumnaEsLaEscalaDeSuMes() {
        ImportacionEscala.Propuesta p = importacion.proponer(EscalaDePrueba.hoja());

        assertThat(p.listaDeMeses()).containsExactly(OCT, NOV);
        MesPropuesto oct = p.meses().getFirst();
        assertThat(oct.valorHora()).isEqualByComparingTo("3250");
        assertThat(oct.valores().get(BASICO.getId())).isEqualByComparingTo("500000");
        assertThat(oct.valores().get(AIS.getId())).isEqualByComparingTo("50000");
        assertThat(oct.valores().get(VIATICO.getId())).isEqualByComparingTo("200000");
        assertThat(p.meses().get(1).valores().get(BASICO.getId())).isEqualByComparingTo("545000");
        assertThat(oct.reemplaza()).isFalse();
    }

    @Test
    void laAsignacionExtraordinariaEsLaFilaDel6Porciento() {
        MesPropuesto oct = importacion.proponer(EscalaDePrueba.hoja()).meses().getFirst();

        // La fila sin "6%" vale 80.000; la del 6%, 75.200, que es la que se cobra.
        assertThat(oct.valores().get(EXTRAORDINARIA.getId())).isEqualByComparingTo("75200");
    }

    @Test
    void losArticulos13Y28YLaOtraAsignacionSeIgnoran() {
        ImportacionEscala.Propuesta p = importacion.proponer(EscalaDePrueba.hoja());

        assertThat(p.ignoradas()).containsExactly("ASIG. EXT. NO REMUNERATIVA", "ART. 13", "ART. 28", "ART. 13 Y 28");
        assertThat(p.meses().getFirst().valores()).hasSize(CONCEPTOS.size() - 1); // todos menos las nocturnas
    }

    @Test
    void loQueLaHojaNoTraeSeMantieneDeLaEscalaAnterior() {
        MesPropuesto oct = importacion.proponer(EscalaDePrueba.hoja()).meses().getFirst();

        assertThat(oct.valores().get(CCT.getId())).isEqualByComparingTo("98985");
        assertThat(oct.valores().get(JUBILACION.getId())).isEqualByComparingTo("11");
        assertThat(oct.mantenidos()).extracting(Valor::nombre)
                .containsExactly("CCT planta nuclear", "Jubilación", "Obra social", "Ley 19.032", "Sindicato");
        assertThat(oct.mantenidos().getFirst().ahora()).isEqualByComparingTo("98985");
    }

    @Test
    void cadaValorTraeSuCambioContraLaEscalaAnterior() {
        ImportacionEscala.Propuesta p = importacion.proponer(EscalaDePrueba.hoja());

        // Octubre contra agosto (la escala que había) y noviembre contra octubre (la columna anterior de la hoja).
        Valor basicoOct = leido(p.meses().getFirst(), BASICO.getId());
        assertThat(basicoOct.antes()).isEqualByComparingTo("460000");
        assertThat(basicoOct.variacion()).isEqualByComparingTo("8.7");
        Valor basicoNov = leido(p.meses().get(1), BASICO.getId());
        assertThat(basicoNov.antes()).isEqualByComparingTo("500000");
        assertThat(basicoNov.variacion()).isEqualByComparingTo("9.0");
        assertThat(p.meses().getFirst().leidos().getFirst().clase()).isEqualTo(ImportacionEscala.Clase.HORA_NORMAL);
    }

    @Test
    void losValoresDeHoraDeLaHojaQuedanEnLaEscalaYLosUsaLaCalculadora() {
        MesPropuesto nov = importacion.proponer(EscalaDePrueba.hoja()).meses().get(1);
        EscalaSueldo escala = new EscalaSueldo(nov.mes().atDay(1), nov.valorHora(), nov.valores());

        assertThat(nov.valores().get(AL_50.getId())).isEqualByComparingTo("5313.79");
        assertThat(nov.valores()).doesNotContainKey(NOCTURNAS.getId());
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, escala,
                Map.of(AL_50.getId(), bd("10"), AL_100.getId(), bd("2"), NOCTURNAS.getId(), bd("30")), Map.of());
        // 10 × 5.313,79 (no 10 × 3.542,50 × 1,5 = 53.137,50), 2 × 7.085,03 y las nocturnas con la normal × 8/60.
        assertThat(importe(r, AL_50.getId())).isEqualByComparingTo("53137.90");
        assertThat(importe(r, AL_100.getId())).isEqualByComparingTo("14170.06");
        assertThat(importe(r, NOCTURNAS.getId())).isEqualByComparingTo("14170.00");
    }

    private static BigDecimal importe(CalculadoraSueldo.Resultado r, Long id) {
        return r.lineas().stream().filter(l -> id.equals(l.conceptoId())).findFirst().orElseThrow().importe();
    }

    @Test
    void unValorPropioDeHoraQueLaHojaYaNoTraeNoSeArrastra() {
        Map<Long, BigDecimal> conHora = new HashMap<>(EscalaDePrueba.agosto().getValores());
        conHora.put(AL_50.getId(), bd("4600"));
        when(repos.escalas().findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.of(new EscalaSueldo(LocalDate.of(2026, 8, 1), bd("3000"), conHora)));
        HojaEscala sinHoras = new HojaEscala(List.of(new HojaEscala.Columna(OCT,
                List.of(HojaEscala.Fila.de("BASICO", "$ 500.000,00"), HojaEscala.Fila.de("HORAS NORMALES", "3.250")))));

        MesPropuesto oct = importacion.proponer(sinHoras).meses().getFirst();

        assertThat(oct.valores()).doesNotContainKey(AL_50.getId());
        assertThat(oct.control()).isNull();
    }

    @Test
    void elControlConValoresNetosDaBienConLosDescuentosDeLaEscala() {
        ImportacionEscala.Propuesta p = importacion.proponer(EscalaDePrueba.hoja());

        assertThat(p.meses()).allSatisfy(m -> assertThat(m.control().ok()).isTrue());
        assertThat(p.meses().getFirst().control().calculado()).isEqualByComparingTo("795200");
    }

    @Test
    void elControlUsaEl20PorcientoFijoDeLaHojaYNoMisDescuentos() {
        // Mis descuentos son otros (22 %), pero la hoja calcula con el 20 %: el control igual cierra.
        Map<Long, BigDecimal> otros = new HashMap<>(EscalaDePrueba.agosto().getValores());
        otros.put(JUBILACION.getId(), bd("13"));
        when(repos.escalas().findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.of(new EscalaSueldo(LocalDate.of(2026, 8, 1), bd("3000"), otros)));

        ImportacionEscala.Control control = importacion.proponer(EscalaDePrueba.hoja()).meses().getFirst().control();

        assertThat(control.ok()).isTrue();
        assertThat(control.porcentaje()).isEqualByComparingTo("20");
    }

    @Test
    void elControlFallaSiUnValorDeLaHojaSeLeyoMal() {
        // El básico leído con un dígito de más: (5.000.000 + 150.000) × 0,80 + 275.200 no da los 795.200 de la hoja.
        List<String> octubre = new java.util.ArrayList<>(OCTUBRE);
        octubre.set(0, "$ 5.000.000,00");
        HojaEscala hoja = new HojaEscala(List.of(EscalaDePrueba.columna(OCT, octubre)));

        ImportacionEscala.Control control = importacion.proponer(hoja).meses().getFirst().control();

        assertThat(control.ok()).isFalse();
        assertThat(control.hoja()).isEqualByComparingTo("795200");
        assertThat(control.calculado()).isEqualByComparingTo("4395200");
    }

    @Test
    void sinEscalaAnteriorLosDescuentosSonLosMiosPorDefecto() {
        when(repos.escalas().findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.empty());

        MesPropuesto oct = importacion.proponer(EscalaDePrueba.hoja()).meses().getFirst();

        assertThat(oct.mantenidos()).filteredOn(v -> v.clase() == ImportacionEscala.Clase.PORCENTAJE)
                .extracting(Valor::ahora).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("11"), bd("3"), bd("3"), bd("2.5"));
        // No vienen de ninguna escala: sin "antes".
        assertThat(oct.mantenidos()).filteredOn(v -> v.clase() == ImportacionEscala.Clase.PORCENTAJE)
                .allSatisfy(v -> assertThat(v.antes()).isNull());
        assertThat(oct.valores()).doesNotContainKey(CCT.getId());
    }

    @Test
    void noviembreSinHorasExtraDaElNetoACobrar() {
        // Mi escala anterior: el CCT (que la hoja no trae) y mis descuentos 11 + 3 + 3 + 2,5 % (19,5 %).
        Map<Long, BigDecimal> mios = new HashMap<>(EscalaDePrueba.agosto().getValores());
        mios.put(EscalaDePrueba.SINDICATO.getId(), bd("2.5"));
        when(repos.escalas().findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.of(new EscalaSueldo(LocalDate.of(2026, 10, 1), bd("3300"), mios)));
        HojaEscala nov = new HojaEscala(List.of(new HojaEscala.Columna(NOV, List.of(
                HojaEscala.Fila.de("BASICO", "$ 608.101,00"), HojaEscala.Fila.de("PRESENTISMO", "$ 107.516,00"),
                HojaEscala.Fila.de("A.I.S.", "$ 62.094,00"), HojaEscala.Fila.de("ASIG. EXT. NO REMUNERATIVA 6%",
                        "$ 107.365,46"), HojaEscala.Fila.de("VIATICOS", "$ 300.000,00"),
                HojaEscala.Fila.de("HORAS NORMALES", "$ 3.660,00"),
                // (608.101 + 107.516 + 62.094) × 0,80 + 107.365,46 + 300.000
                HojaEscala.Fila.de("VALORES NETOS", "$ 1.029.534,26")))));

        MesPropuesto m = importacion.proponer(nov).meses().getFirst();
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS,
                new EscalaSueldo(NOV.atDay(1), m.valorHora(), m.valores()), Map.of(), Map.of());

        assertThat(m.control().ok()).isTrue();
        assertThat(r.bruto()).isEqualByComparingTo("876696.00");
        assertThat(r.descuentos()).isEqualByComparingTo("170955.72");
        assertThat(r.netoTotal()).isEqualByComparingTo("1113105.74");
        // Cada descuento con su plata: 11 % = 96.436,56 y 2,5 % = 21.917,40.
        assertThat(importe(r, JUBILACION.getId())).isEqualByComparingTo("96436.56");
        assertThat(importe(r, EscalaDePrueba.SINDICATO.getId())).isEqualByComparingTo("21917.40");
    }

    @Test
    void unaDiferenciaDeCentavosEnElControlSePerdona() {
        List<String> octubre = new java.util.ArrayList<>(OCTUBRE);
        octubre.set(EscalaDePrueba.FILAS.indexOf("VALORES NETOS"), "$ 795.203,40");
        HojaEscala hoja = new HojaEscala(List.of(EscalaDePrueba.columna(OCT, octubre)));

        assertThat(importacion.proponer(hoja).meses().getFirst().control().ok()).isTrue();
    }

    @Test
    void unImporteConFormatoInvalidoNoSeUsaYSeDiceCual() {
        List<String> noviembre = new java.util.ArrayList<>(NOVIEMBRE);
        noviembre.set(0, "$ 54S.000,00");
        HojaEscala hoja = new HojaEscala(List.of(EscalaDePrueba.columna(NOV, noviembre)));

        assertThatThrownBy(() -> importacion.proponer(hoja)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nov-26 · BASICO").hasMessageContaining("$ 54S.000,00");
    }

    @Test
    void unaEscalaQueYaExisteSeMuestraYSeReemplazaAlGuardar() {
        EscalaSueldo existente = mock(EscalaSueldo.class);
        when(existente.getId()).thenReturn(40L);
        when(existente.getValorHora()).thenReturn(bd("3500"));
        Map<Long, BigDecimal> valores = new HashMap<>(EscalaDePrueba.agosto().getValores());
        valores.put(BASICO.getId(), bd("540000"));
        when(existente.getValores()).thenReturn(valores);
        when(repos.escalas().findByVigenteDesde(NOV.atDay(1))).thenReturn(Optional.of(existente));
        EscalaSueldo guardada = mock(EscalaSueldo.class);
        when(guardada.getId()).thenReturn(41L);
        when(repos.sueldo().editarEscala(eq(40L), any(), any(), anyMap())).thenReturn(existente);
        when(repos.sueldo().crearEscala(any(), any(), anyMap())).thenReturn(guardada);

        ImportacionEscala.Propuesta p = importacion.proponer(EscalaDePrueba.hoja());

        MesPropuesto nov = p.meses().get(1);
        assertThat(nov.reemplaza()).isTrue();
        // Lo que cambia es contra la escala de noviembre que ya está, no contra octubre.
        assertThat(leido(nov, BASICO.getId()).antes()).isEqualByComparingTo("540000");
        List<ImportacionEscala.Guardada> guardadas = importacion.guardar(p, List.of(OCT, NOV));
        assertThat(guardadas).extracting(ImportacionEscala.Guardada::reemplazada).containsExactly(false, true);
        verify(repos.sueldo()).editarEscala(eq(40L), eq(NOV.atDay(1)), eq(bd("3542.50")), anyMap());
        verify(repos.sueldo()).crearEscala(eq(OCT.atDay(1)), eq(bd("3250.00")), anyMap());
    }

    @Test
    void seGuardanSoloLosMesesElegidos() {
        EscalaSueldo guardada = mock(EscalaSueldo.class);
        when(repos.sueldo().crearEscala(any(), any(), anyMap())).thenReturn(guardada);
        ImportacionEscala.Propuesta p = importacion.proponer(EscalaDePrueba.hoja());

        assertThat(importacion.guardar(p, List.of(NOV))).extracting(ImportacionEscala.Guardada::mes)
                .containsExactly(NOV);
        verify(repos.sueldo(), never()).crearEscala(eq(OCT.atDay(1)), any(), anyMap());
        assertThatThrownBy(() -> importacion.guardar(p, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaPlanillaSinMesNoSePropone() {
        HojaEscala sinMes = new HojaEscala(List.of(EscalaDePrueba.columna(null, OCTUBRE)));

        assertThatThrownBy(() -> importacion.proponer(sinMes)).isInstanceOf(IllegalArgumentException.class);
        assertThat(importacion.proponer(sinMes.conMes(OCT)).listaDeMeses()).containsExactly(OCT);
    }

    @Test
    void unAliasNuevoNoPuedeLeerseIgualQueOtro() {
        when(repos.alias().findByClave("ais")).thenReturn(Optional.of(EscalaDePrueba.ALIAS.get(2)));
        when(repos.conceptos().existsById(AIS.getId())).thenReturn(true);

        assertThatThrownBy(() -> importacion.crearAlias("A I S", DestinoAlias.CONCEPTO, AIS.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A.I.S.");
        assertThatThrownBy(() -> importacion.crearAlias("Bono", DestinoAlias.CONCEPTO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
