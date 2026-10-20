package ar.gastito.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.ia.Prompts;

class TranscripcionServiceTest {

    private final ClienteTranscripcion cliente = mock(ClienteTranscripcion.class);
    private final CatalogoService catalogos = mock(CatalogoService.class);
    private final TranscripcionService servicio = new TranscripcionService(cliente, catalogos, new Prompts());

    private static final Catalogo CATALOGO = new Catalogo(
            List.of(new Categoria(1L, "Kiosco", "kiosco", "Golosinas.", "🍬"),
                    new Categoria(2L, "Supermercado", "super", "Almacén.", "🛒")),
            List.of(new MedioPago("Efectivo", "efectivo"), new MedioPago("Débito", "debito"),
                    new MedioPago("QR", "qr")),
            List.of(new Cuenta("Galicia", "galicia"), new Cuenta("Mercado Pago", "mp")),
            Optional.empty());

    @Test
    void elPromptLlevaLosNombresDelCatalogo() {
        assertThat(servicio.prompt(CATALOGO))
                .contains("Categorías: Kiosco, Supermercado.")
                .contains("Medios de pago: Efectivo, Débito, QR.")
                .contains("Cuentas: Galicia, Mercado Pago.")
                .doesNotContain("{{")
                .doesNotEndWith("\n");
    }

    @Test
    void elPromptEsCortoParaWhisper() {
        // Whisper usa solo los últimos ~224 tokens del prompt: tiene que entrar con margen.
        assertThat(servicio.prompt(CATALOGO).length()).isLessThan(400);
    }

    @Test
    void transcribeConElPromptDelCatalogo() {
        byte[] audio = { 1, 2, 3 };
        when(catalogos.cargar()).thenReturn(CATALOGO);
        when(cliente.transcribir(eq(audio), eq("voz.oga"), anyString())).thenReturn("kiosco 1500");

        assertThat(servicio.transcribir(audio, "voz.oga")).isEqualTo("kiosco 1500");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cliente).transcribir(eq(audio), eq("voz.oga"), prompt.capture());
        assertThat(prompt.getValue()).contains("Galicia", "Mercado Pago", "Débito");
    }
}
