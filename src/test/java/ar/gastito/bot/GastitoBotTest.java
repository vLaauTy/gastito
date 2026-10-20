package ar.gastito.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GastitoBotTest {

    @Test
    void elNombreDelAudioLlevaUnaExtensionQueWhisperReconoce() {
        assertThat(GastitoBot.nombreArchivo("nota.m4a", "audio/mp4")).isEqualTo("nota.m4a");
        assertThat(GastitoBot.nombreArchivo(null, "audio/mpeg")).isEqualTo("audio.mp3");
        assertThat(GastitoBot.nombreArchivo("sin extension", "audio/mp4")).isEqualTo("audio.m4a");
        assertThat(GastitoBot.nombreArchivo(null, "audio/x-wav")).isEqualTo("audio.wav");
        assertThat(GastitoBot.nombreArchivo(null, "audio/ogg")).isEqualTo("audio.ogg");
        assertThat(GastitoBot.nombreArchivo(null, null)).isEqualTo("audio.ogg");
    }
}
