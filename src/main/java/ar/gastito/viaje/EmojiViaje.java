package ar.gastito.viaje;

import java.text.Normalizer;
import java.util.List;

/** Un emoji para el nombre del viaje si no se eligió uno: montaña, playa o avión. Se cambia en el dashboard. */
final class EmojiViaje {

    private static final List<String> MONTANA = List.of("bariloche", "mendoza", "ushuaia", "calafate", "chalten",
            "san martin de los andes", "villa la angostura", "esquel", "cordoba", "salta", "jujuy", "tucuman",
            "cerro", "nieve", "esqui", "andes", "montana", "sierras", "tafi", "purmamarca");
    private static final List<String> PLAYA = List.of("mar del plata", "pinamar", "gesell", "costa", "playa",
            "brasil", "florianopolis", "rio", "buzios", "cancun", "caribe", "punta del este", "necochea", "miramar",
            "las grutas", "puerto madryn", "bahia");

    private EmojiViaje() {
    }

    static String para(String nombre) {
        String n = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        if (MONTANA.stream().anyMatch(n::contains)) {
            return "🏔";
        }
        if (PLAYA.stream().anyMatch(n::contains)) {
            return "🏖";
        }
        return "✈️";
    }
}
