package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;

/** Valores inventados: bruto 843.000 y neto total 1.114.400 con 10 h al 50%, 6 al 100% y 30 nocturnas. */
class ComandosSueldoTest {

    static final ConceptoSueldo BASICO = concepto(1, "Sueldo básico", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null);
    static final ConceptoSueldo AL_50 = concepto(5, "Horas al 50%", TipoConceptoSueldo.HORAS, 3, 2);
    static final ConceptoSueldo AL_100 = concepto(6, "Horas al 100%", TipoConceptoSueldo.HORAS, 2, 1);
    static final ConceptoSueldo NOCTURNAS = concepto(7, "Horas nocturnas", TipoConceptoSueldo.HORAS, 8, 60);
    static final ConceptoSueldo VIATICO = concepto(8, "Viático", TipoConceptoSueldo.NO_REMUNERATIVO, null, null);
    static final ConceptoSueldo JUBILACION = concepto(10, "Jubilación", TipoConceptoSueldo.DESCUENTO_PORCENTAJE, null, null);
    static final List<ConceptoSueldo> CONCEPTOS = List.of(BASICO, AL_50, AL_100, NOCTURNAS, VIATICO, JUBILACION);
    static final EscalaSueldo ESCALA = new EscalaSueldo(LocalDate.of(2026, 7, 1), new BigDecimal("3000"), Map.of(
            BASICO.getId(), new BigDecimal("750000"), VIATICO.getId(), new BigDecimal("440000"),
            JUBILACION.getId(), new BigDecimal("20")));

    private final SueldoService sueldo = mock(SueldoService.class);
    private final ComandosSueldo comandos = new ComandosSueldo(sueldo, new FormatoMensajes(RELOJ), RELOJ);

    private static ConceptoSueldo concepto(long id, String nombre, TipoConceptoSueldo tipo, Integer num, Integer den) {
        return ConceptoSueldo.conId(id, new ConceptoSueldo(nombre, tipo, num, den, (int) id));
    }

    private static Map<Long, BigDecimal> horas() {
        return Map.of(AL_50.getId(), new BigDecimal("10"), AL_100.getId(), new BigDecimal("6"),
                NOCTURNAS.getId(), new BigDecimal("30"));
    }

    @BeforeEach
    void setUp() {
        when(sueldo.conceptosDeHoras()).thenReturn(List.of(AL_50, AL_100, NOCTURNAS));
        // Hoy (RELOJ) es octubre de 2026.
        when(sueldo.calcular(eq(YearMonth.of(2026, 10)), any(), any())).thenAnswer(inv -> new SueldoService.VistaCalculo(
                YearMonth.of(2026, 10), false, 1L, ESCALA.getVigenteDesde(),
                CalculadoraSueldo.calcular(CONCEPTOS, ESCALA, inv.getArgument(1), Map.of())));
        when(sueldo.horasPorOrden(any())).thenAnswer(inv -> {
            List<BigDecimal> cantidades = inv.getArgument(0);
            return Map.of(AL_50.getId(), cantidades.get(0), AL_100.getId(), cantidades.get(1),
                    NOCTURNAS.getId(), cantidades.get(2));
        });
    }

    private Accion.Enviar enviar(Accion accion) {
        return (Accion.Enviar) accion;
    }

    @Test
    void lasHorasDelMesEnUnaFraseArmanLaTarjetaConGuardarEditarYCancelar() {
        String frase = "este mes hice 10 horas al 50, 6 al 100 y 30 nocturnas";
        assertThat(comandos.pareceCalculo(frase)).isTrue();

        Accion.Enviar tarjeta = enviar(comandos.calcularDeTexto(frase));

        assertThat(tarjeta.html()).contains("Sueldo de octubre 2026", "10 h horas al 50%", "30 h horas nocturnas",
                "Bruto ", "$843.000", "−$168.600", "$674.400", "$440.000", "$1.114.400", "Escala vigente desde 1/7/2026");
        assertThat(tarjeta.teclado().botones()).extracting(Teclado.Boton::datos)
                .containsExactly("cg:202610:10_6_30", "ce:202610:10_6_30", "cc:202610:10_6_30");
        // Es una cuenta en vivo: nada se guarda hasta tocar Guardar.
        verify(sueldo, never()).guardar(any(), any(), any());
    }

    @Test
    void calcularConNumerosAsignaLasHorasEnOrden() {
        Accion.Enviar tarjeta = enviar(comandos.calcularDeComando("/calcular 10 6 30"));

        assertThat(tarjeta.html()).contains("$1.114.400");
        assertThat(tarjeta.teclado().botones().get(0).datos()).isEqualTo("cg:202610:10_6_30");
    }

    @Test
    void calcularSinNumerosExplicaComoUsarlo() {
        String texto = enviar(comandos.calcularDeComando("/calcular")).html();

        assertThat(texto).contains("este mes hice 10 horas al 50", "horas al 50%, horas al 100%, horas nocturnas")
                .doesNotContain("/calcular");
    }

    @Test
    void calcularConAlgoQueNoEsUnNumeroDiceQueNoEntendio() {
        assertThat(enviar(comandos.calcularDeComando("/calcular diez")).html()).startsWith("No entendí las horas");
    }

    @Test
    void sinEscalaDelMesElMensajeDiceQueFaltaCargarla() {
        when(sueldo.calcular(any(), any(), any())).thenThrow(new IllegalArgumentException(
                "No hay una escala de sueldo para octubre 2026. Cargala en el dashboard (Ingresos → Calculadora)."));

        assertThat(enviar(comandos.calcularDeComando("/calcular 10 6 30")).html()).contains("No hay una escala");
    }

    @Test
    void guardarGuardaElCalculoYSacaLosBotones() {
        when(sueldo.guardar(eq(YearMonth.of(2026, 10)), any(), any())).thenReturn(new SueldoService.VistaCalculo(
                YearMonth.of(2026, 10), true, 1L, ESCALA.getVigenteDesde(),
                CalculadoraSueldo.calcular(CONCEPTOS, ESCALA, horas(), Map.of())));

        List<Accion> acciones = comandos.boton(100, "cg:202610:10_6_30");

        verify(sueldo).guardar(YearMonth.of(2026, 10), horas(), Map.of());
        Accion.Editar editada = (Accion.Editar) acciones.get(0);
        assertThat(editada.mensajeId()).isEqualTo(100);
        assertThat(editada.html()).startsWith("✅ <b>Cálculo guardado</b>").contains("$1.114.400");
        assertThat(editada.teclado().isVacio()).isTrue();
        assertThat(acciones.get(1)).isEqualTo(new Accion.Avisar("✅ Guardado"));
    }

    @Test
    void cancelarNoGuardaNada() {
        List<Accion> acciones = comandos.boton(100, "cc:202610:10_6_30");

        verify(sueldo, never()).guardar(any(), any(), any());
        assertThat(((Accion.Editar) acciones.get(0)).html()).startsWith("❌ <b>Cálculo cancelado</b>");
    }

    @Test
    void editarPideMandarDeNuevoLasHoras() {
        Accion.Editar editada = (Accion.Editar) comandos.boton(100, "ce:202610:10_6_30").get(0);

        assertThat(editada.html()).contains("contame de nuevo").doesNotContain("/calcular");
        assertThat(editada.teclado().isVacio()).isTrue();
    }

    @Test
    void unBotonRotoSoloAvisa() {
        assertThat(comandos.boton(100, "cg:xx:1")).containsExactly(new Accion.Avisar("Botón inválido"));
    }

    @Test
    void reconoceSusBotones() {
        assertThat(ComandosSueldo.esBoton("cg:202610:10_6_30")).isTrue();
        assertThat(ComandosSueldo.esBoton("ce:202610:1")).isTrue();
        assertThat(ComandosSueldo.esBoton("cc:202610:1")).isTrue();
        assertThat(ComandosSueldo.esBoton("c:15")).isFalse();
        assertThat(ComandosSueldo.esBoton("g:15")).isFalse();
        assertThat(ComandosSueldo.esBoton(null)).isFalse();
    }

    @Test
    void unGastoNoEsUnCalculo() {
        assertThat(comandos.pareceCalculo("super 8050")).isFalse();
        assertThat(comandos.pareceCalculo("2 horas de estacionamiento 1500")).isFalse();
    }

    @Test
    void calculoMuestraElDelMesConceptoPorConcepto() {
        CalculadoraSueldo.Resultado r = CalculadoraSueldo.calcular(CONCEPTOS, ESCALA, horas(), Map.of());
        when(sueldo.calculoDelMes(YearMonth.of(2026, 10))).thenReturn(Optional.of(
                new CalculoSueldo(YearMonth.of(2026, 10), 1L, r, LocalDateTime.of(2026, 10, 31, 10, 0))));

        String texto = enviar(comandos.verCalculo()).html();

        assertThat(texto).contains("Sueldo de octubre 2026</b> (guardado)", "Sueldo básico", "$750.000",
                "Horas al 50% (10 h)", "$45.000", "Horas nocturnas (30 h)", "$12.000", "Jubilación", "−$168.600",
                "$1.114.400");
    }

    @Test
    void calculoSinNadaGuardadoDiceComoCalcular() {
        when(sueldo.calculoDelMes(any())).thenReturn(Optional.empty());

        assertThat(enviar(comandos.verCalculo()).html()).contains("Todavía no calculaste el sueldo de octubre 2026");
    }
}
