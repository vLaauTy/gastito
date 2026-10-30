package ar.gastito.sueldo.escala;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.sueldo.SueldoProperties;
import ar.gastito.ticket.ImagenTicket;

class LectorFotoEscalaTest {

    static LecturaEscalaIa respuestaInventada() throws IOException {
        try (InputStream json = LectorFotoEscalaTest.class.getResourceAsStream("/escalas/hoja-inventada-ia.json")) {
            return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(json, LecturaEscalaIa.class);
        }
    }

    @Test
    void laFotoSeLeeUnaSolaVezConElPropositoEscala() throws IOException {
        ClienteIa ia = mock(ClienteIa.class);
        ImagenTicket imagenes = mock(ImagenTicket.class);
        Prompts prompts = mock(Prompts.class);
        when(imagenes.preparar(any())).thenReturn(new ImagenTicket.Imagen(new byte[] {1}, 10, 10));
        when(prompts.cargar(eq(LectorFotoEscala.PROMPT), anyMap())).thenReturn("instrucciones");
        when(ia.extraerDeImagen(anyString(), any(), anyString(), anyLong(), eq(LecturaEscalaIa.class), any()))
                .thenReturn(new ClienteIa.Respuesta<>(respuestaInventada(), 1L));
        LectorFotoEscala lector = new LectorFotoEscala(ia, prompts, imagenes,
                new SueldoProperties(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.05"), 15, 3000),
                Clock.systemDefaultZone());

        LectorFotoEscala.Resultado r = lector.leer(new byte[] {9}, "la escala nueva");

        assertThat(r).isInstanceOf(LectorFotoEscala.Leida.class);
        verify(ia).extraerDeImagen(anyString(), any(), anyString(), eq(3000L), eq(LecturaEscalaIa.class),
                eq(PropositoIa.ESCALA));
    }

    @Test
    void laHojaDeLaFotoPasaLosControlesConElSeparadorCambiado() throws IOException {
        HojaEscala hoja = ((LectorFotoEscala.Leida) LectorFotoEscala.convertir(respuestaInventada())).hoja();

        ImportacionEscala.Propuesta p = EscalaDePrueba.repos().importacion().proponer(hoja);

        assertThat(p.listaDeMeses()).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11));
        // "$ 545,000,00" en la hoja: se lee 545.000.
        assertThat(p.meses().get(1).valores().get(EscalaDePrueba.BASICO.getId())).isEqualByComparingTo("545000");
        assertThat(p.meses()).allSatisfy(m -> assertThat(m.control().ok()).isTrue());
    }

    @Test
    void siNoEsLaHojaOEstaBorrosaNoSeLee() throws IOException {
        LecturaEscalaIa leida = respuestaInventada();

        assertThat(LectorFotoEscala.convertir(new LecturaEscalaIa("OTRO", "", List.of())))
                .isInstanceOf(LectorFotoEscala.NoEsEscala.class);
        assertThat(LectorFotoEscala.convertir(new LecturaEscalaIa("ESCALA", "borrosa", leida.columnas())))
                .isInstanceOf(LectorFotoEscala.Ilegible.class);
        assertThat(LectorFotoEscala.convertir(new LecturaEscalaIa("ESCALA", "",
                List.of(new LecturaEscalaIa.Columna("¿?", leida.columnas().getFirst().filas())))))
                .isInstanceOf(LectorFotoEscala.Ilegible.class);
        assertThat(LectorFotoEscala.convertir(null)).isInstanceOf(LectorFotoEscala.Ilegible.class);
    }
}
