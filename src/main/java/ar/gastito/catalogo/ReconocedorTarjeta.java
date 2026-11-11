package ar.gastito.catalogo;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Qué tarjeta de crédito nombra un mensaje, por sus alias ("con la master", "con la de mercado pago"). Un alias que
 * también nombra una cuenta ("galicia", "mercado pago", "mp") es débil: solo, sin "tarjeta" ni "crédito", es dudoso
 * (en un gasto es Contado con ese detalle; para el ciclo de la tarjeta alcanza). Sin IA: sale de las palabras clave
 * de la base.
 */
public final class ReconocedorTarjeta {

    /** Algo que dice que se pagó con crédito ("con tarjeta", "con crédito"), salvo que sea de débito. */
    private static final Pattern CREDITO = Pattern.compile("\\b(tarjeta|credito)\\b");
    /** Algo que dice que fue plata en una cuenta. */
    private static final Pattern EN_CUENTA = Pattern.compile(
            "\\b(debito|qr|transferencia|transferi|transfer|en cuenta|saldo|dinero en cuenta|plata en cuenta)\\b");

    private ReconocedorTarjeta() {
    }

    /**
     * @param tarjeta la nombrada, o null si no se nombró ninguna (o se nombraron dos)
     * @param dudosa  se la nombró con un alias débil y no se sabe si fue la tarjeta o plata en la cuenta
     */
    public record Mencion(TarjetaCredito tarjeta, boolean dudosa) {

        static final Mencion NINGUNA = new Mencion(null, false);

        public boolean clara() {
            return tarjeta != null && !dudosa;
        }
    }

    /**
     * @param medio el medio de pago que ya se entendió (puede ser null): con crédito, un alias débil alcanza; con
     *              otro medio, no hay tarjeta
     */
    public static Mencion reconocer(String texto, MedioPago medio, Catalogo catalogo) {
        if (texto == null || texto.isBlank() || catalogo.tarjetas().isEmpty()) {
            return Mencion.NINGUNA;
        }
        if (medio != null && !medio.esTarjetaDeCredito()) {
            return Mencion.NINGUNA;
        }
        String t = normalizar(texto);
        Set<String> deCuentas = palabrasDeCuentas(catalogo.cuentas());
        List<TarjetaCredito> fuertes = catalogo.tarjetas().stream()
                .filter(c -> c.alias().stream().anyMatch(a -> !deCuentas.contains(a) && contiene(t, a))).toList();
        if (fuertes.size() == 1) {
            return new Mencion(fuertes.getFirst(), false);
        }
        if (fuertes.size() > 1) {
            return Mencion.NINGUNA;
        }
        List<TarjetaCredito> debiles = catalogo.tarjetas().stream()
                .filter(c -> c.alias().stream().anyMatch(a -> contiene(t, a))).toList();
        if (debiles.size() != 1) {
            return Mencion.NINGUNA;
        }
        boolean credito = (medio != null && medio.esTarjetaDeCredito())
                || (CREDITO.matcher(t).find() && !t.contains("tarjeta de debito"));
        if (credito) {
            return new Mencion(debiles.getFirst(), false);
        }
        return EN_CUENTA.matcher(t).find() ? Mencion.NINGUNA : new Mencion(debiles.getFirst(), true);
    }

    /** La tarjeta que nombra un texto con un alias fuerte o con "tarjeta" ("la tarjeta de mercado pago cierra…"). */
    public static Optional<TarjetaCredito> nombrada(String texto, Catalogo catalogo) {
        Mencion m = reconocer(texto, null, catalogo);
        return m.tarjeta() == null ? Optional.empty() : Optional.of(m.tarjeta());
    }

    private static Set<String> palabrasDeCuentas(List<Cuenta> cuentas) {
        return cuentas.stream()
                .flatMap(c -> Stream.concat(Stream.of(c.getNombre()),
                        c.getPalabrasClave() == null ? Stream.empty() : Stream.of(c.getPalabrasClave().split(","))))
                .map(ReconocedorTarjeta::normalizar).filter(p -> !p.isEmpty()).collect(Collectors.toSet());
    }

    private static boolean contiene(String texto, String alias) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(alias) + "(?![a-z0-9])").matcher(texto).find();
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").strip();
    }
}
