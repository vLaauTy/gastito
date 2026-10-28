package ar.gastito.sueldo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({SueldoService.class, SueldoServiceTest.Config.class})
class SueldoServiceTest {

    @TestConfiguration
    @EnableConfigurationProperties(SueldoProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-27T15:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
        }
    }

    @Autowired
    SueldoService sueldo;

    Map<String, Long> ids = new HashMap<>();

    @BeforeEach
    void conceptos() {
        sueldo.listarConceptos().forEach(c -> ids.put(c.getNombre(), c.getId()));
    }

    /** Valores inventados sobre los conceptos que deja la migración. */
    private EscalaSueldo cargarEscala(String desde, String hora, String basico) {
        Map<Long, BigDecimal> valores = new HashMap<>();
        valores.put(ids.get("Sueldo básico"), new BigDecimal(basico));
        valores.put(ids.get("Presentismo"), new BigDecimal("100000"));
        valores.put(ids.get("Adicional CCT"), new BigDecimal("90000"));
        valores.put(ids.get("Asignación integración social"), new BigDecimal("60000"));
        valores.put(ids.get("Viático"), new BigDecimal("300000"));
        valores.put(ids.get("Asignación extraordinaria"), new BigDecimal("140000"));
        valores.put(ids.get("Jubilación"), new BigDecimal("11"));
        valores.put(ids.get("Obra social"), new BigDecimal("3"));
        valores.put(ids.get("Ley 19.032"), new BigDecimal("3"));
        valores.put(ids.get("Sindicato"), new BigDecimal("3"));
        return sueldo.crearEscala(LocalDate.parse(desde), new BigDecimal(hora), valores);
    }

    private Map<Long, BigDecimal> horas() {
        return sueldo.horasPorOrden(List.of(new BigDecimal("10"), new BigDecimal("6"), new BigDecimal("30")));
    }

    @Test
    void laMigracionDejaLosConceptosYElBotAsignaLasHorasEnOrden() {
        assertThat(sueldo.conceptosDeHoras()).extracting(ConceptoSueldo::getNombre)
                .containsExactly("Horas al 50%", "Horas al 100%", "Horas nocturnas");
        assertThat(horas()).containsEntry(ids.get("Horas nocturnas"), new BigDecimal("30"));
    }

    @Test
    void sinEscalaNoSePuedeCalcular() {
        assertThatThrownBy(() -> sueldo.calcular(YearMonth.of(2026, 10), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No hay una escala");
    }

    @Test
    void guardaElCalculoDelMesConLaEscalaVigenteYLoReemplazaAlRecalcular() {
        EscalaSueldo escala = cargarEscala("2026-01-01", "3000", "500000");

        SueldoService.VistaCalculo v = sueldo.guardar(YearMonth.of(2026, 10), horas(), Map.of());

        assertThat(v.guardado()).isTrue();
        assertThat(v.escalaId()).isEqualTo(escala.getId());
        assertThat(v.resultado().netoTotal()).isEqualByComparingTo("1114400");
        assertThat(sueldo.calculoDelMes(YearMonth.of(2026, 10)).orElseThrow().getLineas()).hasSize(13);

        sueldo.guardar(YearMonth.of(2026, 10), Map.of(), Map.of());
        assertThat(sueldo.calculosEntre(YearMonth.of(2026, 10), YearMonth.of(2026, 10))).hasSize(1);
        assertThat(sueldo.calculoDelMes(YearMonth.of(2026, 10)).orElseThrow().getNetoTotal())
                .isEqualByComparingTo("1040000");
    }

    @Test
    void cadaMesUsaLaEscalaQueRegiaYElHistorialNoCambia() {
        cargarEscala("2026-01-01", "3000", "500000");
        EscalaSueldo nueva = cargarEscala("2026-07-01", "3300", "550000");

        SueldoService.VistaCalculo junio = sueldo.guardar(YearMonth.of(2026, 6), horas(), Map.of());
        SueldoService.VistaCalculo julio = sueldo.guardar(YearMonth.of(2026, 7), horas(), Map.of());

        assertThat(junio.resultado().bruto()).isEqualByComparingTo("843000");
        assertThat(julio.escalaId()).isEqualTo(nueva.getId());
        assertThat(julio.resultado().bruto()).isEqualByComparingTo("902300");

        // Cambiar la escala nueva no mueve el cálculo ya guardado de julio.
        Map<Long, BigDecimal> otrosValores = new HashMap<>(nueva.getValores());
        otrosValores.put(ids.get("Sueldo básico"), new BigDecimal("999999"));
        sueldo.editarEscala(nueva.getId(), nueva.getVigenteDesde(), nueva.getValorHora(), otrosValores);
        assertThat(sueldo.calculoDelMes(YearMonth.of(2026, 7)).orElseThrow().getBruto())
                .isEqualByComparingTo("902300");
    }

    @Test
    void laEscalaDeUnaFechaALaMitadDelMesRigeDesdeElPrimeroDeEseMes() {
        EscalaSueldo escala = cargarEscala("2026-03-15", "3000", "500000");

        assertThat(escala.getVigenteDesde()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(sueldo.escalaVigente(YearMonth.of(2026, 3)).getId()).isEqualTo(escala.getId());
        assertThatThrownBy(() -> sueldo.escalaVigente(YearMonth.of(2026, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noHayDosEscalasDesdeLaMismaFecha() {
        cargarEscala("2026-01-01", "3000", "500000");

        assertThatThrownBy(() -> cargarEscala("2026-01-20", "3100", "510000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sinCalculoNoHayConQueComparar() {
        assertThat(sueldo.comparar(YearMonth.of(2026, 10), new BigDecimal("1110000"))).isEmpty();
    }

    @Test
    void cobrarUnPocoMenosQuedaDentroDeLaTolerancia() {
        cargarEscala("2026-01-01", "3000", "500000");
        sueldo.guardar(YearMonth.of(2026, 10), horas(), Map.of());

        // 1.114.400 calculados, 1.110.000 cobrados: −4.400 (−0,4%).
        SueldoService.Comparacion c = sueldo.comparar(YearMonth.of(2026, 10), new BigDecimal("1110000"))
                .orElseThrow();

        assertThat(c.calculado()).isEqualByComparingTo("1114400");
        assertThat(c.diferencia()).isEqualByComparingTo("-4400");
        assertThat(c.porcentaje()).isEqualByComparingTo("-0.4");
        assertThat(c.fuera()).isFalse();
    }

    @Test
    void cobrarMasDeUnPorcientoDeDiferenciaSeMarca() {
        cargarEscala("2026-01-01", "3000", "500000");
        sueldo.guardar(YearMonth.of(2026, 10), horas(), Map.of());

        SueldoService.Comparacion c = sueldo.comparar(YearMonth.of(2026, 10), new BigDecimal("1100000"))
                .orElseThrow();

        assertThat(c.diferencia()).isEqualByComparingTo("-14400");
        assertThat(c.porcentaje()).isEqualByComparingTo("-1.3");
        assertThat(c.fuera()).isTrue();
    }

    @Test
    void justoEnElUnoPorCientoNoSeMarca() {
        SueldoService.Comparacion c = sueldo.comparar(YearMonth.of(2026, 10), new BigDecimal("1000000"),
                new BigDecimal("990000"));

        assertThat(c.fuera()).isFalse();
        assertThat(sueldo.comparar(YearMonth.of(2026, 10), new BigDecimal("1000000"), new BigDecimal("1010001"))
                .fuera()).isTrue();
    }

    @Test
    void sePuedeCrearUnConceptoNuevoYDesactivarlo() {
        ConceptoSueldo nuevo = sueldo.guardarConcepto(null, "Premio", TipoConceptoSueldo.REMUNERATIVO_FIJO, null,
                null, null, true);
        assertThat(nuevo.getId()).isNotNull();

        sueldo.guardarConcepto(nuevo.getId(), "Premio", TipoConceptoSueldo.REMUNERATIVO_FIJO, null, null, 99, false);
        assertThat(sueldo.listarConceptos()).filteredOn(c -> c.getId().equals(nuevo.getId()))
                .singleElement().satisfies(c -> assertThat(c.isActivo()).isFalse());
    }
}
