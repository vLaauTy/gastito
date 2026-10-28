package ar.gastito.recibo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Los recibos de estos tests son inventados: ningún dato es de una persona ni de una empresa reales. */
class ValidadorReciboTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final Clock RELOJ = Clock.fixed(LocalDateTime.of(2026, 11, 5, 10, 0).atZone(ARGENTINA).toInstant(),
            ARGENTINA);

    private final ValidadorRecibo validador = new ValidadorRecibo(RELOJ);

    static LecturaReciboIa.Concepto concepto(String descripcion, String grupo, String importe) {
        return new LecturaReciboIa.Concepto(descripcion, grupo, importe);
    }

    /** Octubre de 2026: 1.250.000 de bruto, 212.500 de descuentos y 1.037.500 de neto. */
    static LecturaReciboIa octubre() {
        return new LecturaReciboIa("RECIBO_SUELDO", "", "Empresa Ejemplo", "2026-10", "03/11/2026",
                "sueldo_mensual",
                List.of(concepto("Sueldo básico", "REMUNERATIVO", "1.000.000,00"),
                        concepto("Antigüedad", "REMUNERATIVO", "150.000,00"),
                        concepto("Suma no remunerativa", "NO_REMUNERATIVO", "100.000,00"),
                        concepto("Jubilación 11%", "DESCUENTO", "137.500,00"),
                        concepto("Obra social 3%", "DESCUENTO", "37.500,00"),
                        concepto("Ley 19.032 3%", "DESCUENTO", "37.500,00")),
                "1.250.000,00", "212.500,00", "1.037.500,00");
    }

    private ReciboLeido valido(LecturaReciboIa ia) {
        ValidadorRecibo.Resultado r = validador.validar(ia);
        assertThat(r).isInstanceOf(ValidadorRecibo.Valido.class);
        return ((ValidadorRecibo.Valido) r).recibo();
    }

    private LecturaReciboIa con(LecturaReciboIa base, String tipoDocumento, String problema, String periodo,
            String fechaPago, String tipo, List<LecturaReciboIa.Concepto> conceptos, String bruto,
            String descuentos, String neto) {
        return new LecturaReciboIa(tipoDocumento, problema, base.empleador(), periodo, fechaPago, tipo, conceptos,
                bruto, descuentos, neto);
    }

    @Test
    void unReciboQueCierraSeLeeSinAvisos() {
        ReciboLeido r = valido(octubre());

        assertThat(r.aviso()).isNull();
        assertThat(r.empleador()).isEqualTo("Empresa Ejemplo");
        assertThat(r.periodo()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(r.fechaPago()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(r.tipo()).isEqualTo(TipoRecibo.SUELDO_MENSUAL);
        assertThat(r.bruto()).isEqualByComparingTo("1250000");
        assertThat(r.descuentos()).isEqualByComparingTo("212500");
        assertThat(r.neto()).isEqualByComparingTo("1037500");
        assertThat(r.conceptos()).hasSize(6);
        assertThat(r.conceptos().get(3)).isEqualTo(new ReciboLeido.Concepto("Jubilación 11%",
                GrupoConcepto.DESCUENTO, new BigDecimal("137500.00")));
    }

    @Test
    void conLaToleranciaDeUnPesoCierra() {
        LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "2026-10", "", "sueldo_mensual",
                octubre().conceptos(), "1250000", "212500", "1037500.90");

        assertThat(valido(ia).aviso()).isNull();
    }

    @Test
    void siNoCierraSeGuardaPeroAvisaQueNoCierra() {
        LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "2026-10", "", "sueldo_mensual",
                octubre().conceptos(), "1250000", "212500", "1037300");

        ReciboLeido r = valido(ia);

        assertThat(r.neto()).isEqualByComparingTo("1037300");
        assertThat(r.aviso()).contains("No cierra").contains("$1.037.500,00").contains("$1.037.300,00")
                .contains("$200,00");
    }

    @Test
    void avisaSiLosDescuentosSumadosNoSonLosImpresos() {
        LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "2026-10", "", "sueldo_mensual",
                octubre().conceptos(), "1250000", "250000", "1037500");

        ReciboLeido r = valido(ia);

        assertThat(r.aviso()).contains("Los descuentos suman $212.500,00").contains("$250.000,00");
        assertThat(r.descuentos()).isEqualByComparingTo("250000");
    }

    @Test
    void sinNetoNoSeGuardaNada() {
        for (String neto : new String[] {null, "", "  ", "ilegible", "0", "0,00"}) {
            LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "2026-10", "", "sueldo_mensual",
                    octubre().conceptos(), "1250000", "212500", neto);

            assertThat(validador.validar(ia)).as("neto %s", neto)
                    .isEqualTo(new ValidadorRecibo.Ilegible(ValidadorRecibo.Motivo.SIN_NETO));
        }
    }

    @Test
    void sinBrutoNiTotalDeDescuentosSeCalculanConLosConceptos() {
        LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "2026-10", "", "sueldo_mensual",
                octubre().conceptos(), null, null, "1037500");

        ReciboLeido r = valido(ia);

        assertThat(r.bruto()).isEqualByComparingTo("1250000");
        assertThat(r.descuentos()).isEqualByComparingTo("212500");
        assertThat(r.aviso()).isNull();
    }

    @Test
    void unAguinaldoEsDeTipoAguinaldo() {
        for (String tipo : new String[] {"aguinaldo", "SAC", "Sueldo anual complementario"}) {
            LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "2026-06", "", tipo, octubre().conceptos(),
                    "1250000", "212500", "1037500");

            assertThat(valido(ia).tipo()).as(tipo).isEqualTo(TipoRecibo.AGUINALDO);
        }
        assertThat(TipoRecibo.AGUINALDO.claseIngreso()).isEqualTo(ar.gastito.movimiento.ClaseIngreso.AGUINALDO);
        assertThat(TipoRecibo.SUELDO_MENSUAL.claseIngreso()).isEqualTo(ar.gastito.movimiento.ClaseIngreso.SUELDO);
    }

    @Test
    void losTiposDeRecibo() {
        assertThat(ValidadorRecibo.tipo("vacaciones")).isEqualTo(TipoRecibo.VACACIONES);
        assertThat(ValidadorRecibo.tipo("liquidacion_final")).isEqualTo(TipoRecibo.LIQUIDACION_FINAL);
        assertThat(ValidadorRecibo.tipo("sueldo_mensual")).isEqualTo(TipoRecibo.SUELDO_MENSUAL);
        assertThat(ValidadorRecibo.tipo(null)).isEqualTo(TipoRecibo.SUELDO_MENSUAL);
    }

    @Test
    void unTicketOOtraCosaNoEsUnRecibo() {
        LecturaReciboIa ticket = con(octubre(), "TICKET", "", "", "", "", List.of(), null, null, null);
        LecturaReciboIa otro = con(octubre(), "OTRO", "", "", "", "", List.of(), null, null, null);
        LecturaReciboIa raro = con(octubre(), "una foto de un gato", "", "", "", "", List.of(), null, null, null);

        assertThat(validador.validar(ticket)).isEqualTo(new ValidadorRecibo.NoEsRecibo(TipoDocumento.TICKET));
        assertThat(validador.validar(otro)).isEqualTo(new ValidadorRecibo.NoEsRecibo(TipoDocumento.OTRO));
        assertThat(validador.validar(raro)).isEqualTo(new ValidadorRecibo.NoEsRecibo(TipoDocumento.OTRO));
    }

    @Test
    void unaRespuestaMalFormadaNoRompeNada() {
        assertThat(validador.validar(null)).isEqualTo(new ValidadorRecibo.Ilegible(ValidadorRecibo.Motivo.BORROSO));
        assertThat(validador.validar(new LecturaReciboIa(null, null, null, null, null, null, null, null, null, null)))
                .isEqualTo(new ValidadorRecibo.NoEsRecibo(TipoDocumento.OTRO));

        LecturaReciboIa basura = new LecturaReciboIa("RECIBO_SUELDO", null, null, "2026-10", "32/13/2026", null,
                java.util.Arrays.asList(null, concepto(null, "DESCUENTO", "100"), concepto("Algo", "DESCUENTO", "abc"),
                        concepto("Algo raro", "cualquier cosa", "500"), concepto("Básico", "REMUNERATIVO", "1.000,00"),
                        concepto("Jubilación", "DESCUENTO", "-110,00")),
                "x", "y", "890");

        ReciboLeido r = valido(basura);

        assertThat(r.empleador()).isEqualTo(ValidadorRecibo.SIN_EMPLEADOR);
        assertThat(r.fechaPago()).isNull();
        assertThat(r.tipo()).isEqualTo(TipoRecibo.SUELDO_MENSUAL);
        assertThat(r.conceptos()).extracting(ReciboLeido.Concepto::descripcion).containsExactly("Básico", "Jubilación");
        // El descuento viene con signo menos pero se guarda positivo: el grupo dice que resta.
        assertThat(r.conceptos().get(1).importe()).isEqualByComparingTo("110");
        assertThat(r.aviso()).contains("1 concepto no se pudo ubicar");
    }

    @Test
    void unReciboBorrosoOSinPeriodoSePideDeNuevo() {
        LecturaReciboIa borroso = con(octubre(), "RECIBO_SUELDO", "borroso", "2026-10", "", "", List.of(), null, null,
                "1037500");
        LecturaReciboIa sinPeriodo = con(octubre(), "RECIBO_SUELDO", "", "no se lee", "", "", octubre().conceptos(),
                "1250000", "212500", "1037500");

        assertThat(validador.validar(borroso)).isEqualTo(new ValidadorRecibo.Ilegible(ValidadorRecibo.Motivo.BORROSO));
        assertThat(validador.validar(sinPeriodo))
                .isEqualTo(new ValidadorRecibo.Ilegible(ValidadorRecibo.Motivo.SIN_PERIODO));
    }

    @Test
    void sinPeriodoUsaElMesDeLaFechaDePago() {
        LecturaReciboIa ia = con(octubre(), "RECIBO_SUELDO", "", "", "03/11/2026", "", octubre().conceptos(),
                "1250000", "212500", "1037500");

        assertThat(valido(ia).periodo()).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void elPeriodoSeEntiendeEnVariosFormatos() {
        YearMonth octubre = YearMonth.of(2026, 10);
        for (String texto : new String[] {"2026-10", "10/2026", "10-2026", "10/26", "octubre 2026", "Octubre de 2026",
                "oct-26", "OCT 2026"}) {
            assertThat(validador.periodo(texto)).as(texto).contains(octubre);
        }
        assertThat(validador.periodo("2026-13")).isEmpty();
        assertThat(validador.periodo("")).isEmpty();
        assertThat(validador.periodo("hoy")).isEmpty();
        // Hoy es 5/11/2026: un mes futuro de verdad o uno de hace 20 años es un error de lectura.
        assertThat(validador.periodo("2027-05")).isEmpty();
        assertThat(validador.periodo("2006-01")).isEmpty();
        assertThat(validador.periodo("2026-12")).contains(YearMonth.of(2026, 12));
    }

    @Test
    void laFechaDePagoSeEntiendeConBarrasOGuiones() {
        assertThat(validador.fecha("03/11/2026")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(validador.fecha("3-11-26")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(validador.fecha("2026-11-03")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(validador.fecha("Buenos Aires, 03/11/2026")).isNull();
        assertThat(validador.fecha("31/02/2026")).isNull();
    }

    @Test
    void noGuardaCuilCbuNiLegajoEnLosTextosLibres() {
        LecturaReciboIa ia = new LecturaReciboIa("RECIBO_SUELDO", "", "Empresa 30-12345678-9", "2026-10", "", "",
                List.of(concepto("Sueldo CUIL 20-11111111-2", "REMUNERATIVO", "1000"),
                        concepto("Legajo 4455", "REMUNERATIVO", "10"),
                        concepto("Descuento", "DESCUENTO", "100")),
                null, null, "910");

        ReciboLeido r = valido(ia);

        assertThat(r.empleador()).isEqualTo("Empresa");
        assertThat(r.conceptos().get(0).descripcion()).isEqualTo("Sueldo CUIL");
        assertThat(r.conceptos()).extracting(ReciboLeido.Concepto::descripcion).doesNotContain("Legajo 4455");
    }

    @Test
    void elGrupoSeEntiendeAunqueLaIaLoEscribaDistinto() {
        assertThat(ValidadorRecibo.grupo("REMUNERATIVO")).isEqualTo(GrupoConcepto.REMUNERATIVO);
        assertThat(ValidadorRecibo.grupo("NO_REMUNERATIVO")).isEqualTo(GrupoConcepto.NO_REMUNERATIVO);
        assertThat(ValidadorRecibo.grupo("no remunerativo")).isEqualTo(GrupoConcepto.NO_REMUNERATIVO);
        assertThat(ValidadorRecibo.grupo("Descuentos")).isEqualTo(GrupoConcepto.DESCUENTO);
        assertThat(ValidadorRecibo.grupo("deducción")).isEqualTo(GrupoConcepto.DESCUENTO);
        assertThat(ValidadorRecibo.grupo("remuneraciones")).isEqualTo(GrupoConcepto.REMUNERATIVO);
        assertThat(ValidadorRecibo.grupo("otra cosa")).isNull();
        assertThat(ValidadorRecibo.grupo(null)).isNull();
    }
}
