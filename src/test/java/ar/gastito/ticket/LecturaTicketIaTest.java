package ar.gastito.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.models.messages.MessageCreateParams;

import ar.gastito.ia.Prompts;

class LecturaTicketIaTest {

    @Test
    void elSdkAceptaElSchemaDeLaLectura() {
        // El SDK valida el schema que genera desde el record al armar el pedido, sin llamar a la API.
        assertThatCode(() -> MessageCreateParams.builder()
                .model("claude-haiku-5-5")
                .maxTokens(4096)
                .addUserMessage("ticket")
                .outputConfig(LecturaTicketIa.class)
                .build()).doesNotThrowAnyException();
    }

    @Test
    void elPromptReemplazaTodosLosMarcadores() {
        String prompt = new Prompts().cargar(LectorTicket.PROMPT, Map.of(
                "categorias", "- Supermercado", "mediosPago", "- Débito", "cuentas", "- Galicia",
                "ejemplos", "(todavía no hay)", "hoy", "jueves 22/10/2026"));

        assertThat(prompt).doesNotContain("{{").contains("- Supermercado").contains("\"18/10/26\" es el 18 de octubre");
    }
}
