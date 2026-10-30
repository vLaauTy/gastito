package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.ConceptoSueldoRepository;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.EscalaSueldoRepository;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;
import ar.gastito.sueldo.escala.AliasEscala;
import ar.gastito.sueldo.escala.AliasEscalaRepository;
import ar.gastito.sueldo.escala.DestinoAlias;
import ar.gastito.sueldo.escala.HojaEscala;
import ar.gastito.sueldo.escala.ImportacionEscala;
import ar.gastito.sueldo.escala.ImportacionesPendientes;
import ar.gastito.sueldo.escala.LectorExcelEscala;
import ar.gastito.sueldo.escala.LectorFotoEscala;

/** La escala desde la hoja en el bot, con una hoja inventada de dos meses (no son los valores reales). */
class ConversacionEscalasTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    private static ConceptoSueldo concepto(long id, String nombre, TipoConceptoSueldo tipo) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, tipo, tipo == TipoConceptoSueldo.HORAS ? 3 : null,
                tipo == TipoConceptoSueldo.HORAS ? 2 : null, (int) id));
    }

    private static final List<ConceptoSueldo> CONCEPTOS = List.of(
            concepto(1, "Sueldo básico", TipoConceptoSueldo.REMUNERATIVO_FIJO),
            concepto(2, "CCT planta nuclear", TipoConceptoSueldo.REMUNERATIVO_FIJO),
            concepto(3, "Horas al 50%", TipoConceptoSueldo.HORAS),
            concepto(4, "Viático", TipoConceptoSueldo.NO_REMUNERATIVO),
            concepto(5, "Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE),
            concepto(6, "Obra social", TipoConceptoSueldo.DESCUENTO_PORCENTAJE),
            concepto(7, "Ley 19.032", TipoConceptoSueldo.DESCUENTO_PORCENTAJE),
            concepto(8, "Sindicato", TipoConceptoSueldo.DESCUENTO_PORCENTAJE));

    private static final List<AliasEscala> ALIAS = List.of(
            new AliasEscala("BASICO", DestinoAlias.CONCEPTO, 1L),
            new AliasEscala("HORAS AL 50 %", DestinoAlias.CONCEPTO, 3L),
            new AliasEscala("VIATICOS", DestinoAlias.CONCEPTO, 4L),
            new AliasEscala("HORAS NORMALES", DestinoAlias.HORA_NORMAL, null),
            new AliasEscala("VALORES NETOS", DestinoAlias.VALORES_NETOS, null));

    private final ConceptoSueldoRepository conceptos = mock(ConceptoSueldoRepository.class);
    private final EscalaSueldoRepository escalas = mock(EscalaSueldoRepository.class);
    private final AliasEscalaRepository alias = mock(AliasEscalaRepository.class);
    private final SueldoService sueldo = mock(SueldoService.class);
    private final LectorFotoEscala fotos = mock(LectorFotoEscala.class);
    private final LectorExcelEscala excels = mock(LectorExcelEscala.class);
    private final ImportacionesPendientes pendientes = new ImportacionesPendientes(RELOJ);
    private final ConversacionEscalas conversacion = new ConversacionEscalas(fotos, excels,
            new ImportacionEscala(conceptos, escalas, alias, sueldo), pendientes, new FormatoMensajes(RELOJ), RELOJ);

    private static EscalaSueldo agosto(String jubilacion) {
        return new EscalaSueldo(LocalDate.of(2026, 8, 1), new BigDecimal("3000"), Map.of(1L, new BigDecimal("460000"),
                2L, new BigDecimal("98985"), 4L, new BigDecimal("190000"), 5L, new BigDecimal(jubilacion),
                6L, new BigDecimal("3"), 7L, new BigDecimal("3"), 8L, new BigDecimal("3")));
    }

    /** Netos = básico × 0,80 + viáticos: 400.000 + 200.000 en octubre y 436.000 + 205.000 en noviembre. */
    private static HojaEscala hoja() {
        return new HojaEscala(List.of(
                new HojaEscala.Columna(OCT, List.of(HojaEscala.Fila.de("BASICO", "$ 500.000,00"),
                        HojaEscala.Fila.de("VIATICOS", "$ 200.000,00"), HojaEscala.Fila.de("HORAS NORMALES", "$ 3.250,00"),
                        HojaEscala.Fila.de("HORAS AL 50 %", "$ 4.875,10"), HojaEscala.Fila.de("VALORES NETOS", "$ 600.000,00"),
                        HojaEscala.Fila.de("ART. 13", "$ 12.345,67"))),
                new HojaEscala.Columna(NOV, List.of(HojaEscala.Fila.de("BASICO", "$ 545.000,00"),
                        HojaEscala.Fila.de("VIATICOS", "$ 205.000,00"), HojaEscala.Fila.de("HORAS NORMALES", "$ 3.542,50"),
                        HojaEscala.Fila.de("VALORES NETOS", "$ 641.000,00")))));
    }

    @BeforeEach
    void preparar() throws Exception {
        when(conceptos.findAllByOrderByOrdenAscIdAsc()).thenReturn(CONCEPTOS);
        when(alias.findAll()).thenReturn(ALIAS);
        when(escalas.findByVigenteDesde(any())).thenReturn(Optional.empty());
        when(escalas.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.of(agosto("11")));
        when(excels.leer(any())).thenReturn(hoja());
        EscalaSueldo guardada = mock(EscalaSueldo.class);
        when(guardada.getId()).thenReturn(50L);
        when(sueldo.crearEscala(any(), any(), anyMap())).thenReturn(guardada);
        when(sueldo.editarEscala(any(), any(), any(), anyMap())).thenReturn(guardada);
    }

    private static ExcelRecibido excel() {
        return new ExcelRecibido(100L, null, () -> new byte[] {1});
    }

    private Accion.Enviar leer() {
        return (Accion.Enviar) conversacion.leerExcel(excel());
    }

    private static String datos(Accion.Enviar a, String texto) {
        return a.teclado().botones().stream().filter(b -> b.texto().contains(texto)).findFirst().orElseThrow().datos();
    }

    @Test
    void laFotoOElExcelSeReconocenPorElTexto() {
        assertThat(ConversacionEscalas.mencionaEscala("la escala de noviembre")).isTrue();
        assertThat(ConversacionEscalas.mencionaEscala("Paritaria nueva")).isTrue();
        assertThat(ConversacionEscalas.mencionaEscala("hoja del sindicato")).isTrue();
        assertThat(ConversacionEscalas.mencionaEscala("recibo de sueldo")).isFalse();
        assertThat(ConversacionEscalas.mencionaEscala("efectivo")).isFalse();
        assertThat(GastitoBot.esExcel("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null)).isTrue();
        assertThat(GastitoBot.esExcel("application/octet-stream", "Escala SOM.XLSX")).isTrue();
        assertThat(GastitoBot.esExcel("application/pdf", "recibo.pdf")).isFalse();
    }

    @Test
    void elResumenMuestraCadaMesConSusCambiosElControlYLoQueSeMantiene() {
        Accion.Enviar tarjeta = leer();

        assertThat(tarjeta.html()).contains("📄 <b>Escalas desde la hoja del sindicato</b>")
                .contains("<b>oct-26</b>  Hora normal $3.250 (+8,3%) · Sueldo básico $500.000 (+8,7%)")
                .contains("Horas al 50% $4.875,10")
                .contains("<b>nov-26</b>  Hora normal $3.542,50 (+9%) · Sueldo básico $545.000 (+9%)")
                .contains("✅ control")
                .contains("Se mantienen: CCT planta nuclear $98.985, descuentos 11 + 3 + 3 + 3 %")
                .contains("No uso: ART. 13");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("✅ Guardar todas", "📅 Elegir meses", "❌ Cancelar");
    }

    @Test
    void siLosDescuentosNoCierranConLaHojaSeAvisa() {
        when(escalas.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any()))
                .thenReturn(Optional.of(agosto("13")));

        assertThat(leer().html()).contains("⚠️ Con tus descuentos da $590.000 y la hoja dice $600.000: revisá los "
                + "porcentajes");
    }

    @Test
    void guardarTodasCreaUnaEscalaPorMes() {
        Accion.Enviar tarjeta = leer();

        List<Accion> acciones = conversacion.boton(10, datos(tarjeta, "Guardar todas"));

        verify(sueldo).crearEscala(eq(OCT.atDay(1)), eq(new BigDecimal("3250.00")), anyMap());
        verify(sueldo).crearEscala(eq(NOV.atDay(1)), eq(new BigDecimal("3542.50")), anyMap());
        assertThat(((Accion.Editar) acciones.getFirst()).html()).contains("✅ <b>Escalas guardadas</b>: oct-26, nov-26");
        assertThat(acciones).contains(new Accion.Avisar("✅ Guardado"));
        // Ya no hay nada pendiente: el botón otra vez no guarda dos veces.
        assertThat(conversacion.boton(10, datos(tarjeta, "Guardar todas")))
                .contains(new Accion.QuitarBotones(10));
        verify(sueldo, times(2)).crearEscala(any(), any(), anyMap());
    }

    @Test
    void sePuedenElegirLosMesesQueSeGuardan() {
        Accion.Enviar tarjeta = leer();

        Accion.Editar meses = (Accion.Editar) conversacion.boton(10, datos(tarjeta, "Elegir meses")).getFirst();
        assertThat(meses.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("☑️ oct-26", "☑️ nov-26", "✅ Guardar elegidas (2)", "↩️ Volver");
        String alternarOctubre = meses.teclado().botones().getFirst().datos();
        Accion.Editar sinOctubre = (Accion.Editar) conversacion.boton(10, alternarOctubre).getFirst();
        assertThat(sinOctubre.teclado().botones()).extracting(Teclado.Boton::texto)
                .contains("⬜ oct-26", "✅ Guardar elegidas (1)");

        conversacion.boton(10, sinOctubre.teclado().botones().get(2).datos());

        verify(sueldo).crearEscala(eq(NOV.atDay(1)), any(), anyMap());
        verify(sueldo, never()).crearEscala(eq(OCT.atDay(1)), any(), anyMap());
    }

    @Test
    void siYaHayEscalaParaUnMesSeMuestraQueCambiaYSeReemplaza() {
        Map<Long, BigDecimal> valores = new HashMap<>(agosto("11").getValores());
        valores.put(1L, new BigDecimal("540000"));
        EscalaSueldo existente = mock(EscalaSueldo.class);
        when(existente.getId()).thenReturn(40L);
        when(existente.getValorHora()).thenReturn(new BigDecimal("3542.50"));
        when(existente.getValores()).thenReturn(valores);
        when(escalas.findByVigenteDesde(NOV.atDay(1))).thenReturn(Optional.of(existente));

        Accion.Enviar tarjeta = leer();

        assertThat(tarjeta.html()).contains("🔁 <b>nov-26</b> ya tiene una escala: se reemplaza. Cambia Sueldo "
                + "básico $540.000 → $545.000 · Viático $190.000 → $205.000.");
        assertThat(tarjeta.teclado().botones().getFirst().texto()).isEqualTo("✅ Guardar y reemplazar");
        List<Accion> acciones = conversacion.boton(10, datos(tarjeta, "reemplazar"));
        verify(sueldo).editarEscala(eq(40L), eq(NOV.atDay(1)), any(), anyMap());
        assertThat(((Accion.Editar) acciones.getFirst()).html()).contains("nov-26 (reemplazada)");
    }

    @Test
    void siLaPlanillaNoDiceElMesSePreguntaConBotones() throws Exception {
        HojaEscala sinMes = new HojaEscala(List.of(new HojaEscala.Columna(null, hoja().columnas().getFirst().filas())));
        when(excels.leer(any())).thenReturn(sinMes);

        Accion.Enviar pregunta = leer();

        assertThat(pregunta.html()).contains("no dice de qué mes");
        // El reloj de prueba está en octubre de 2026: septiembre, octubre y noviembre.
        assertThat(pregunta.teclado().botones()).extracting(Teclado.Boton::texto)
                .containsExactly("sep-26", "oct-26", "nov-26", "❌ Cancelar");
        Accion.Editar tarjeta = (Accion.Editar) conversacion.boton(10, datos(pregunta, "nov-26")).getFirst();
        assertThat(tarjeta.html()).contains("<b>nov-26</b>  Hora normal $3.250");
        assertThat(tarjeta.teclado().botones().getFirst().texto()).isEqualTo("✅ Guardar");
    }

    @Test
    void unExcelQueNoSePuedeUsarSeExplicaYNoSeGuardaNada() {
        when(excels.leer(any())).thenThrow(new IllegalArgumentException("No pude abrir el archivo"));

        assertThat(leer().html()).contains("No pude abrir el archivo").contains("No guardé nada");
        verify(sueldo, never()).crearEscala(any(), any(), anyMap());
    }

    @Test
    void cancelarNoGuardaNada() {
        Accion.Enviar tarjeta = leer();

        List<Accion> acciones = conversacion.boton(10, datos(tarjeta, "Cancelar"));

        assertThat(((Accion.Editar) acciones.getFirst()).html()).contains("no guardé nada");
        verify(sueldo, never()).crearEscala(any(), any(), anyMap());
    }
}
