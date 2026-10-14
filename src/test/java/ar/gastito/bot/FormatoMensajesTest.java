package ar.gastito.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.Cuenta;
import ar.gastito.movimiento.MedioPago;
import ar.gastito.movimiento.MovimientoInterpretado;

class FormatoMensajesTest {

    static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Martes 13/10/2026 16:42. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 13, 16, 42);

    private final FormatoMensajes formato =
            new FormatoMensajes(Clock.fixed(AHORA.atZone(ARGENTINA).toInstant(), ARGENTINA));

    private final Categoria kiosco = new Categoria("Kiosco", "kiosco");

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "3700|$3.700",
            "3700.00|$3.700",
            "1500.5|$1.500,50",
            "1234567.89|$1.234.567,89",
            "0.99|$0,99",
            "100|$100"
    })
    void pesosALaArgentina(String monto, String esperado) {
        assertThat(formato.pesos(new BigDecimal(monto))).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0.00024160|US$ 0,000242",
            "1.5|US$ 1,50",
            "0|US$ 0,00"
    })
    void dolares(String monto, String esperado) {
        assertThat(formato.dolares(new BigDecimal(monto))).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-10-13T16:42|Hoy 16:42",
            "2026-10-13T08:05|Hoy 08:05",
            "2026-10-12T12:00|Ayer",
            "2026-10-12T21:30|Ayer 21:30",
            "2026-10-10T12:00|sáb 10/10",
            "2025-12-20T12:00|20/12/2025"
    })
    void fechas(String fecha, String esperado) {
        assertThat(formato.fecha(LocalDateTime.parse(fecha))).isEqualTo(esperado);
    }

    @Test
    void mes() {
        assertThat(formato.mes(YearMonth.of(2026, 10))).isEqualTo("octubre 2026");
    }

    @Test
    void lineaComoEnElEjemplo() {
        MovimientoInterpretado m = MovimientoInterpretado.conCategoria(new BigDecimal("3700"), "Pan de pancho y papas", kiosco,
                new MedioPago("Efectivo", null), null, null, AHORA);

        assertThat(formato.linea(m)).isEqualTo("Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42");
    }

    @Test
    void lineaSinMedioYConDescripcionIgualALaCategoria() {
        MovimientoInterpretado m = MovimientoInterpretado.conCategoria(new BigDecimal("1500"), "Kiosco", kiosco, null, null,
                null, AHORA);

        assertThat(formato.linea(m)).isEqualTo("Kiosco – $1.500 – Sin medio de pago – Hoy 16:42");
    }

    @Test
    void lineaConCuentaYSinMonto() {
        MovimientoInterpretado m = MovimientoInterpretado.conCategoria(null, "Pan", kiosco, new MedioPago("QR", null),
                new Cuenta("Mercado Pago", null), null, AHORA);

        assertThat(formato.linea(m)).isEqualTo("Kiosco – Pan – QR (Mercado Pago) – Hoy 16:42");
    }
}
