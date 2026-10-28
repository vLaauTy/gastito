package ar.gastito.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TopeIaServiceTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 15, 10, 0);

    @Autowired
    UsoIaRepository usos;

    @Autowired
    AvisoTopeIaRepository avisos;

    private TopeIaService servicio(String tope, LocalDateTime ahora) {
        return new TopeIaService(usos, avisos, new TopeIaProperties(tope == null ? null : new BigDecimal(tope)),
                Clock.fixed(ahora.atZone(ARGENTINA).toInstant(), ARGENTINA));
    }

    private void gastar(LocalDateTime fechaHora, String costo) {
        usos.save(new UsoIa(fechaHora, "claude-haiku-5-5", 1, 1, PropositoIa.CONSULTA, new BigDecimal(costo)));
    }

    @Test
    void conPocoGastadoNoHayAviso() {
        gastar(AHORA.minusDays(1), "1.59");

        assertThat(servicio("2", AHORA).pendiente()).isEmpty();
    }

    @Test
    void alLlegarAl80PorCientoAvisaConElPorcentajeYElTope() {
        gastar(AHORA.minusDays(1), "1.62");

        TopeIaService.Aviso aviso = servicio("2", AHORA).pendiente().orElseThrow();

        assertThat(aviso.umbral()).isEqualTo(80);
        assertThat(aviso.porcentaje()).isEqualTo(81);
        assertThat(aviso.gastadoUsd()).isEqualByComparingTo("1.62");
        assertThat(aviso.topeUsd()).isEqualByComparingTo("2");
        assertThat(aviso.mes()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void justoEnElLimiteDel80PorCientoYaAvisa() {
        gastar(AHORA, "1.60");

        assertThat(servicio("2", AHORA).pendiente()).hasValueSatisfying(a -> assertThat(a.umbral()).isEqualTo(80));
    }

    @Test
    void elAvisoDel80SeMandaUnaSolaVez() {
        gastar(AHORA.minusDays(1), "1.62");
        TopeIaService servicio = servicio("2", AHORA);
        servicio.marcarEnviado(servicio.pendiente().orElseThrow());

        assertThat(servicio.pendiente()).isEmpty();
        // Sigue gastando sin llegar al tope: no se repite.
        gastar(AHORA, "0.30");
        assertThat(servicio.pendiente()).isEmpty();
    }

    @Test
    void siElAvisoNoSeMandoSeVuelveAOfrecer() {
        gastar(AHORA.minusDays(1), "1.62");
        TopeIaService servicio = servicio("2", AHORA);

        assertThat(servicio.pendiente()).isPresent();
        // Telegram falló y nadie lo marcó como enviado.
        assertThat(servicio.pendiente()).isPresent();
    }

    @Test
    void alLlegarAl100PorCientoHayOtroAviso() {
        gastar(AHORA.minusDays(2), "1.62");
        TopeIaService servicio = servicio("2", AHORA);
        servicio.marcarEnviado(servicio.pendiente().orElseThrow());
        gastar(AHORA, "0.45");

        TopeIaService.Aviso aviso = servicio.pendiente().orElseThrow();

        assertThat(aviso.umbral()).isEqualTo(100);
        assertThat(aviso.porcentaje()).isEqualTo(103);
        servicio.marcarEnviado(aviso);
        assertThat(servicio.pendiente()).isEmpty();
    }

    @Test
    void siSaltaDirectoAl100SoloAvisaElDel100YNoVuelveElDel80() {
        gastar(AHORA, "2.50");
        TopeIaService servicio = servicio("2", AHORA);

        TopeIaService.Aviso aviso = servicio.pendiente().orElseThrow();
        assertThat(aviso.umbral()).isEqualTo(100);
        servicio.marcarEnviado(aviso);

        assertThat(servicio.pendiente()).isEmpty();
        assertThat(avisos.count()).isEqualTo(2);
    }

    @Test
    void elMesQueViene_arrancaDeNuevo() {
        gastar(AHORA, "1.70");
        servicio("2", AHORA).marcarEnviado(servicio("2", AHORA).pendiente().orElseThrow());
        LocalDateTime noviembre = LocalDateTime.of(2026, 11, 2, 10, 0);

        assertThat(servicio("2", noviembre).pendiente()).isEmpty();
        gastar(noviembre, "1.70");
        assertThat(servicio("2", noviembre).pendiente()).isPresent();
    }

    @Test
    void lasLlamadasDeOtroMesNoCuentan() {
        gastar(LocalDateTime.of(2026, 9, 20, 9, 0), "5.00");

        assertThat(servicio("2", AHORA).pendiente()).isEmpty();
    }

    @Test
    void sinTopeNoAvisaNunca() {
        gastar(AHORA, "50");

        assertThat(servicio("0", AHORA).pendiente()).isEmpty();
        assertThat(servicio(null, AHORA).pendiente()).isEmpty();
        assertThat(servicio("0", AHORA).topeUsd()).isEmpty();
        assertThat(servicio("0", AHORA).porcentajeDelTope(BigDecimal.ONE)).isEmpty();
    }

    @Test
    void elPorcentajeVaHaciaAbajo() {
        TopeIaService servicio = servicio("2", AHORA);

        assertThat(servicio.porcentajeDelTope(new BigDecimal("1.99"))).contains(99);
        assertThat(servicio.porcentajeDelTope(new BigDecimal("1.62"))).contains(81);
        assertThat(servicio.porcentajeDelTope(new BigDecimal("0"))).contains(0);
    }
}
