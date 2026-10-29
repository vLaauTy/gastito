package ar.gastito.bot;

/** Qué tiene que hacer el bot en Telegram. Los textos van en HTML. */
public sealed interface Accion {

    /**
     * Manda un mensaje nuevo.
     *
     * @param conMenu            si true, va con el teclado fijo de abajo (no se puede combinar con botones inline)
     * @param tarjetaDeMovimiento si es la tarjeta de un gasto, su id: el bot avisa qué mensaje quedó
     */
    record Enviar(String html, Teclado teclado, boolean conMenu, Long tarjetaDeMovimiento) implements Accion {

        public static Enviar texto(String html) {
            return new Enviar(html, Teclado.NINGUNO, false, null);
        }

        public static Enviar conMenu(String html) {
            return new Enviar(html, Teclado.NINGUNO, true, null);
        }

        public static Enviar tarjeta(String html, Teclado teclado, long movimientoId) {
            return new Enviar(html, teclado, false, movimientoId);
        }

        /** Un mensaje con botones que no es la tarjeta de un gasto (un resumen con "Ver anteriores"). */
        public static Enviar conBotones(String html, Teclado teclado) {
            return new Enviar(html, teclado, false, null);
        }

        /** El mismo mensaje con un texto antes (por ejemplo, "🎙 Escuché: …" arriba de lo que se entendió de un audio). */
        public Enviar conPrefijo(String prefijo) {
            return prefijo == null || prefijo.isEmpty() ? this
                    : new Enviar(prefijo + html, teclado, conMenu, tarjetaDeMovimiento);
        }
    }

    /** Saca los botones de un mensaje sin cambiar su texto (por ejemplo, "Ver anteriores" después de tocarlo). */
    record QuitarBotones(int mensajeId) implements Accion {
    }

    /** Cambia el texto y los botones de un mensaje que ya está en el chat. Sin botones, se los saca. */
    record Editar(int mensajeId, String html, Teclado teclado) implements Accion {
    }

    /** Borra un mensaje del chat (por ejemplo, el valor que escribió el usuario al editar un campo). */
    record Borrar(int mensajeId) implements Accion {
    }

    /** Aviso corto arriba del chat al tocar un botón. */
    record Avisar(String texto) implements Accion {
    }
}
