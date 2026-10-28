package ar.gastito.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AvisoArranqueTest {

    @Test
    void sacaElNombreDeLaBaseDeLaUrl() {
        assertThat(AvisoArranque.nombreBase("jdbc:postgresql://192.168.1.50:5432/gastito")).isEqualTo("gastito");
        assertThat(AvisoArranque.nombreBase("jdbc:postgresql://localhost/gastito_prueba?sslmode=disable"))
                .isEqualTo("gastito_prueba");
        assertThat(AvisoArranque.nombreBase("jdbc:h2:file:./data/gastito;MODE=PostgreSQL")).isEqualTo("gastito");
        assertThat(AvisoArranque.nombreBase("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")).isEqualTo("test");
        assertThat(AvisoArranque.nombreBase(null)).isEmpty();
    }

    @Test
    void elMensajeDiceSiEsLaBaseRealOLaDePrueba() {
        assertThat(AvisoArranque.mensaje("jdbc:postgresql://vm:5432/gastito")).contains("<b>real</b>");
        assertThat(AvisoArranque.mensaje("jdbc:postgresql://vm:5432/gastito_prueba"))
                .contains("PRUEBA", "no va a la base real");
        assertThat(AvisoArranque.mensaje("jdbc:postgresql://vm:5432/otra")).contains("otra", "no es la real");
    }

    @Test
    void laH2DelPerfilDevNoEsLaRealAunqueSeLlameIgual() {
        assertThat(AvisoArranque.mensaje("jdbc:h2:file:./data/gastito;MODE=PostgreSQL"))
                .contains("H2", "no es la real").doesNotContain("<b>real</b>");
    }
}
