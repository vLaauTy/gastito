package ar.gastito.interpretacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.Confianza;

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
    void unGastoDudosoPreguntaLaCategoria() {
        InterpretadorService.Interpretado i = interpretar("3700 en pan de pancho y papas, efectivo");

        assertThat(i.confianza()).isNotEqualTo(Confianza.ALTA);
        assertThat(i.movimiento().categoria()).isNull();
        assertThat(i.movimiento().candidatas()).extracting(Categoria::getNombre)
                .contains("Supermercado")
                .hasSizeBetween(2, 3);
        assertThat(i.movimiento().monto()).isEqualByComparingTo("3700");
        assertThat(i.movimiento().medioPago().getNombre()).isEqualTo("Efectivo");
        System.out.println("Uso del mes: " + registro.resumenMesActual());
    }

    @Test
    void unGastoClaroElige() {
        InterpretadorService.Interpretado i = interpretar("ayer pagué 12000 de nafta con la tarjeta");

        assertThat(i.confianza()).isEqualTo(Confianza.ALTA);
        assertThat(i.movimiento().categoria().getNombre()).isEqualTo("Transporte / Moto");
        assertThat(i.movimiento().medioPago().getNombre()).isEqualTo("Tarjeta de crédito");
        assertThat(i.movimiento().tarjeta()).isNotNull();
    }

    private InterpretadorService.Interpretado interpretar(String texto) {
        InterpretadorService.Resultado r = interpretador.interpretar(texto);
        if (r instanceof InterpretadorService.Interpretado i) {
            System.out.printf("%s -> confianza %s, categoría %s, candidatas %s%n", texto, i.confianza(),
                    i.movimiento().categoria() == null ? "-" : i.movimiento().categoria().getNombre(),
                    i.movimiento().candidatas().stream().map(Categoria::getNombre).toList());
            return i;
        }
        throw new AssertionError("No lo interpretó como gasto: " + r);
    }
}
