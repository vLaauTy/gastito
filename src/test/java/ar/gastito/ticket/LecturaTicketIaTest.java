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

        assertThat(prompt).doesNotContain("{{").contains("- Supermercado");
    }

    @Test
    void elPromptExplicaLoQueAprendimosDelTicketDeDia() {
        String prompt = new Prompts().cargar(LectorTicket.PROMPT, Map.of());

        assertThat(prompt)
                .as("comercio de una franquicia").contains("Si aparece \"a cuenta y orden de X\", el comercio es X")
                .as("fechas con guiones").contains("\"22-09-2026 17:44:28\"")
                .as("producto en tres renglones").contains("cada producto ocupa tres renglones")
                .as("la alícuota de IVA no es un precio").contains("alícuota de IVA")
                .as("el ahorro no se resta de nuevo").contains("\"Ahorro ClubDía\"")
                .as("tarjeta sin aclarar").contains("Si solo dice \"Tarjeta\"")
                .as("número de cliente").contains("número de cliente (por ejemplo de ClubDía)");
    }
}
