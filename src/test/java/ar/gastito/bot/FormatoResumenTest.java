package ar.gastito.bot;

import static ar.gastito.bot.DatosDePrueba.RELOJ;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.resumen.DatosResumen;
import ar.gastito.resumen.PeriodoResumen;
import ar.gastito.resumen.Resumen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.resumen.TipoResumen;

class FormatoResumenTest {

    static final LocalDate LUNES = LocalDate.of(2026, 10, 12);

    private final FormatoMensajes formato = new FormatoMensajes(RELOJ);
    private final FormatoResumen formatoResumen = new FormatoResumen(formato, new GraficoMes(formato),
            new DashboardProperties("http://localhost:8090", null));

    @Test
    void laSemanaComoElPedido() {
        String html = formatoResumen.mensaje(semana(false, List.of("Hiciste 11 compras chicas en el kiosco."),
                "Si bajás el kiosco a 3 veces por semana, ahorrás unos $8.900 por mes."), 0);

        assertThat(html).startsWith("""
                📊 <b>Tu semana</b> · 12 al 18 de octubre
                Gastaste <b>$52.300</b> en 23 gastos (+12% que la semana pasada)

                <pre>""");
        assertThat(html).contains("🛒 Supermercado").contains("$21.400").contains("🍬 Kiosco");
        assertThat(html).contains("""
                🐜 Gastos hormiga: 11 compras chicas, $14.800
                🏆 Lo que más compraste: Seven Up lata (4)
                🌱 Días sin gastos: 2
                ❔ 1 gasto sin medio de pago ($3.000)

                💡 <b>Lo que noté</b>
                • Hiciste 11 compras chicas en el kiosco.
                👉 <b>Consejo:</b> Si bajás el kiosco a 3 veces por semana, ahorrás unos $8.900 por mes.

                🖥 Más detalle en el dashboard: <a href="http://localhost:8090">http://localhost:8090</a>""");
        assertThat(html).doesNotContain("Ingresos").doesNotContain("anteriores");
    }

    @Test
    void unaSemanaParcialLoDiceEnElEncabezado() {
        String html = formatoResumen.mensaje(semana(true, List.of(), null), 0);

        assertThat(html).startsWith("""
                📊 <b>Tu semana</b> · 12 al 18 de octubre
                <i>Semana parcial, hasta el jueves 15</i>
                Gastaste <b>$52.300</b> en 23 gastos (+12% que la semana pasada a esta altura)""");
        assertThat(html).doesNotContain("Lo que noté");
    }

    @Test
    void sinTextoDeLaIaSaleSoloConLosNumeros() {
        String html = formatoResumen.mensaje(semana(false, List.of(), null), 0);

        assertThat(html).doesNotContain("💡").doesNotContain("👉").contains("🐜 Gastos hormiga");
    }

    @Test
    void conPocosDatosVaLaLineaFijaEnLugarDelConsejoYLasNotasAlPie() {
        Resumen r = new Resumen(TipoResumen.SEMANAL, LUNES, LUNES.plusDays(6), false, false, "{}",
                List.of("El súper bajó un 29%."), null, false, 9L, LocalDateTime.now());
        String html = formatoResumen.mensaje(new ResumenService.Armado(r, datos("SEMANAL", LUNES, LUNES.plusDays(6),
                false, null, null, true, List.of("1 gasto es de una juntada: lo que te devuelvan todavía no se "
                        + "descuenta del total."))), 0);

        assertThat(html).contains("""
                ❔ 1 gasto sin medio de pago ($3.000)
                <i>ℹ️ 1 gasto es de una juntada: lo que te devuelvan todavía no se descuenta del total.</i>

                💡 <b>Lo que noté</b>
                • El súper bajó un 29%.

                👉 Todavía hay pocos datos para darte un consejo. Seguí cargando tus gastos.

                🖥""");
        assertThat(html).doesNotContain("Consejo:").doesNotContain("etapa");
    }

    @Test
    void elTextoDeLaIaSeEscapa() {
        String html = formatoResumen.mensaje(semana(false, List.of("<b>Ojo</b> & cuidado"), null), 0);

        assertThat(html).contains("• &lt;b&gt;Ojo&lt;/b&gt; &amp; cuidado");
    }

    @Test
    void conAnterioresSinVerLoAvisaYPoneElBoton() {
        PeriodoResumen actual = PeriodoResumen.semanaDe(LocalDate.of(2026, 11, 2));
        ResumenService.Envio envio = new ResumenService.Envio(actual, List.of(
                PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 19)), PeriodoResumen.semanaDe(LocalDate.of(2026, 10, 26))));

        String html = formatoResumen.mensaje(semana(false, List.of(), null), ResumenService.ofrecidos(envio));
        Teclado teclado = formatoResumen.teclado(envio);

        assertThat(html).endsWith("📬 Tenés 2 resúmenes anteriores sin ver.");
        assertThat(teclado.botones()).singleElement().satisfies(b -> {
            assertThat(b.texto()).isEqualTo("📬 Ver anteriores");
            assertThat(b.datos()).isEqualTo("va:S:20261019:20261026");
            assertThat(b.datos().getBytes()).hasSizeLessThanOrEqualTo(64);
        });
        assertThat(FormatoResumen.leerAnteriores(b(teclado)))
                .isEqualTo(new FormatoResumen.Anteriores(TipoResumen.SEMANAL, LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 26)));
        assertThat(formatoResumen.teclado(new ResumenService.Envio(actual, List.of())).isVacio()).isTrue();
    }

    @Test
    void losDatosDeOtrosBotonesNoSonVerAnteriores() {
        assertThat(FormatoResumen.leerAnteriores("g:15")).isNull();
        assertThat(FormatoResumen.leerAnteriores("va:X:20261019:20261026")).isNull();
        assertThat(FormatoResumen.leerAnteriores("va:S:2026:20261026")).isNull();
        assertThat(FormatoResumen.leerAnteriores(null)).isNull();
    }

    @Test
    void elMesConIngresosYCostoDeLaIa() {
        DatosResumen d = datos("MENSUAL", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), false,
                new DatosResumen.Ingresos(new BigDecimal("380000.00"), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), new BigDecimal("380000.00"), new BigDecimal("327700.00"), 14),
                new BigDecimal("0.0123"));
        Resumen r = new Resumen(TipoResumen.MENSUAL, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), false, true,
                "{}", List.of(), null, false, null, LocalDateTime.now());

        String html = formatoResumen.mensaje(new ResumenService.Armado(r, d), 0);

        assertThat(html).startsWith("📊 <b>Tu mes</b> · Septiembre 2026\nGastaste <b>$52.300</b> en 23 gastos "
                + "(+12% que agosto)");
        assertThat(html).contains("💵 Ingresos <b>$380.000</b> · Disponible <b>$327.700</b>\n"
                + "Gastaste el 14% de lo que entró.").contains("🤖 La IA costó US$ 0,0123 en el mes.");
    }

    @Test
    void unaSemanaQueCruzaDeMes() {
        assertThat(FormatoResumen.rango(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)))
                .isEqualTo("26 de octubre al 1 de noviembre");
    }

    // --- Datos ---

    private static String b(Teclado teclado) {
        return teclado.botones().get(0).datos();
    }

    private static ResumenService.Armado semana(boolean parcial, List<String> observaciones, String consejo) {
        LocalDate hasta = parcial ? LUNES.plusDays(3) : LUNES.plusDays(6);
        Resumen r = new Resumen(TipoResumen.SEMANAL, LUNES, hasta, parcial, false, "{}", observaciones, consejo,
                false, null, LocalDateTime.now());
        return new ResumenService.Armado(r, datos("SEMANAL", LUNES, hasta, parcial, null, null));
    }

    private static DatosResumen datos(String tipo, LocalDate desde, LocalDate hasta, boolean parcial,
            DatosResumen.Ingresos ingresos, BigDecimal costoIa) {
        return datos(tipo, desde, hasta, parcial, ingresos, costoIa, false, List.of());
    }

    private static DatosResumen datos(String tipo, LocalDate desde, LocalDate hasta, boolean parcial,
            DatosResumen.Ingresos ingresos, BigDecimal costoIa, boolean pocosDatos, List<String> notas) {
        LocalDate fin = tipo.equals("SEMANAL") ? desde.plusDays(6) : hasta;
        return new DatosResumen(
                new DatosResumen.Periodo(tipo, desde, hasta, fin, parcial, 7),
                new BigDecimal("52300.00"), 23,
                new DatosResumen.Comparacion(desde.minusDays(7), hasta.minusDays(7), new BigDecimal("46700.00"), 12),
                List.of(new DatosResumen.Categoria("Supermercado", "🛒", new BigDecimal("21400.00"), 3,
                                new BigDecimal("30000.00"), -29),
                        new DatosResumen.Categoria("Kiosco", "🍬", new BigDecimal("14800.00"), 11,
                                new BigDecimal("8000.00"), 85)),
                new DatosResumen.GastoGrande("Súper", "Supermercado", new BigDecimal("12000.00"), desde),
                new DatosResumen.Hormiga(new BigDecimal("3000.00"), List.of("Kiosco"), 11, new BigDecimal("14800.00"),
                        new BigDecimal("1345.45"), new BigDecimal("11.0")),
                2,
                new DatosResumen.SinEspecificar(1, new BigDecimal("3000.00")),
                new DatosResumen.Productos(List.of(new DatosResumen.Producto("Seven Up lata", new BigDecimal("4"),
                        new BigDecimal("9000.00"))), List.of()),
                List.of(), ingresos, costoIa, pocosDatos, notas);
    }
}
