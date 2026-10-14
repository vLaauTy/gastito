package ar.gastito.bot;

import java.util.ArrayList;
import java.util.List;

/** Botones inline de un mensaje, por filas. Independiente de la API de Telegram. */
public record Teclado(List<List<Boton>> filas) {

    public static final Teclado NINGUNO = new Teclado(List.of());

    public record Boton(String texto, String datos) {
    }

    public Teclado {
        filas = filas.stream().filter(f -> !f.isEmpty()).map(List::copyOf).toList();
    }

    public boolean isVacio() {
        return filas.isEmpty();
    }

    /** Todos los botones, en orden (útil en tests). */
    public List<Boton> botones() {
        return filas.stream().flatMap(List::stream).toList();
    }

    public static Constructor constructor() {
        return new Constructor();
    }

    public static final class Constructor {
        private final List<List<Boton>> filas = new ArrayList<>();

        public Constructor fila(Boton... botones) {
            filas.add(List.of(botones));
            return this;
        }

        /** Reparte los botones en filas de a {@code porFila}. */
        public Constructor grilla(List<Boton> botones, int porFila) {
            for (int i = 0; i < botones.size(); i += porFila) {
                filas.add(botones.subList(i, Math.min(i + porFila, botones.size())));
            }
            return this;
        }

        public Teclado build() {
            return new Teclado(filas);
        }
    }
}
