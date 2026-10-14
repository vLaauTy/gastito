package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.ia.RegistroUsoIa;

/**
 * Llama a la API real de Anthropic con la key del .env (cuesta fracciones de centavo).
 * No corre con "mvn test". Para correrlo, por ejemplo después de cambiar el prompt:
 *
 * <pre>mvn test -Dtest=InterpretacionRealIT -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@SpringBootTest(properties = "gastito.ia.api-key=${ANTHROPIC_API_KEY}")
@ActiveProfiles("test")
class InterpretacionRealIT {

    @Autowired
    InterpretadorService interpretador;

    @Autowired
    RegistroUsoIa registro;

    @Test
    void interpretaUnGastoConLaApiReal() {
        InterpretadorService.Resultado r = interpretador.interpretar("3700 en pan de pancho y papas, efectivo");
        System.out.println("Resultado: " + r);

        assertThat(r).isInstanceOf(InterpretadorService.Interpretado.class);
        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) r;
        assertThat(i.movimiento().monto()).isEqualByComparingTo("3700");
        assertThat(i.movimiento().medioPago().getNombre()).isEqualTo("Efectivo");
        assertThat(i.usoIaId()).isNotNull();
        System.out.println("Uso del mes: " + registro.resumenMesActual());
    }

    @Test
    void interpretaTarjetaYFechaRelativa() {
        InterpretadorService.Resultado r = interpretador.interpretar("ayer pagué 12000 de nafta con la tarjeta");
        System.out.println("Resultado: " + r);

        InterpretadorService.Interpretado i = (InterpretadorService.Interpretado) r;
        assertThat(i.movimiento().categoria().getNombre()).isEqualTo("Transporte / Moto");
        assertThat(i.movimiento().medioPago().getNombre()).isEqualTo("Tarjeta de crédito");
        assertThat(i.movimiento().tarjeta()).isNotNull();
    }
}
