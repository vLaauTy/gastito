package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;

/**
 * Lee lo que va después de /sueldo, /aguinaldo o /ingreso, sin IA: "380.000", "380 mil galicia",
 * "25000 arreglo de PC a mercado pago", "5000 en efectivo ayer".
 * <p>
 * El monto va primero; si no, tiene que haber uno solo en todo el mensaje. La cuenta se reconoce por su nombre
 * (aunque tenga dos palabras) o por sus palabras clave, y el efectivo por las palabras clave de su medio de pago.
 * Lo que sobra es la descripción.
 */
@Component
public class ParserIngreso {

    /**
     * @param cuenta    null si no se dijo (o si fue en efectivo)
     * @param efectivo  si se dijo que fue en efectivo
     * @param fecha     "hoy", "ayer" o "anteayer" si se dijo; si no, vacío
     */
    public record Resultado(BigDecimal monto, String descripcion, Cuenta cuenta, boolean efectivo, String fecha) {
    }

    private static final Set<String> MULTIPLICADORES = Set.of("mil", "k", "millon", "millones", "m");
    private static final Set<String> CONECTORES = Set.of("a", "en", "de", "desde", "por", "con", "al", "la", "el");
    private static final Set<String> FECHAS = Set.of("hoy", "ayer", "anteayer");

    public Optional<Resultado> parsear(String texto, Catalogo catalogo) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        List<String> tokens = new ArrayList<>(List.of(texto.strip().split("\\s+")));

        Optional<Monto> monto = montoAl(tokens, 0);
        if (monto.isEmpty()) {
            monto = montoUnico(tokens);
        }
        if (monto.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < monto.get().largo(); i++) {
            tokens.remove(monto.get().posicion());
        }

        Cuenta cuenta = null;
        boolean efectivo = false;
        String fecha = "";
        List<String> resto = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String palabra = Textos.normalizar(limpio(tokens.get(i)));
            Cuenta porNombre = i + 1 < tokens.size()
                    ? cuentaPorNombre(palabra + " " + Textos.normalizar(limpio(tokens.get(i + 1))), catalogo)
                    : null;
            Cuenta porClave = cuentaPorClave(palabra, catalogo);
            if (cuenta == null && !efectivo && (porNombre != null || porClave != null)) {
                cuenta = porNombre != null ? porNombre : porClave;
                i += porNombre != null ? 1 : 0;
                sacarConectorFinal(resto);
            } else if (cuenta == null && !efectivo && esEfectivo(palabra, catalogo)) {
                efectivo = true;
                sacarConectorFinal(resto);
            } else if (fecha.isEmpty() && FECHAS.contains(palabra)) {
                fecha = palabra;
            } else if (!palabra.isEmpty()) {
                resto.add(tokens.get(i));
            }
        }
        sacarConectorFinal(resto);
        String descripcion = String.join(" ", resto).replaceAll("[,;.!?]+$", "").strip();
        return Optional.of(new Resultado(monto.get().valor(), descripcion.isEmpty() ? null : descripcion, cuenta,
                efectivo, fecha));
    }

    private record Monto(BigDecimal valor, int posicion, int largo) {
    }

    /** "380.000" o "380 mil" empezando en la posición dada. */
    private static Optional<Monto> montoAl(List<String> tokens, int i) {
        String token = limpio(tokens.get(i));
        if (i + 1 < tokens.size() && MULTIPLICADORES.contains(Textos.normalizar(limpio(tokens.get(i + 1))))) {
            Optional<BigDecimal> conPalabra = Montos.parsearConPalabras(token + " " + limpio(tokens.get(i + 1)));
            if (conPalabra.isPresent()) {
                return Optional.of(new Monto(conPalabra.get(), i, 2));
            }
        }
        return Montos.parsearConPalabras(token).map(v -> new Monto(v, i, 1));
    }

    /** El monto si en todo el mensaje hay exactamente uno. */
    private static Optional<Monto> montoUnico(List<String> tokens) {
        Monto encontrado = null;
        for (int i = 0; i < tokens.size(); i++) {
            Optional<Monto> m = montoAl(tokens, i);
            if (m.isPresent()) {
                if (encontrado != null) {
                    return Optional.empty();
                }
                encontrado = m.get();
                i += encontrado.largo() - 1;
            }
        }
        return Optional.ofNullable(encontrado);
    }

    private static Cuenta cuentaPorNombre(String dosPalabras, Catalogo catalogo) {
        return catalogo.cuentas().stream()
                .filter(c -> Textos.normalizar(c.getNombre()).equals(dosPalabras))
                .findFirst().orElse(null);
    }

    private static Cuenta cuentaPorClave(String palabra, Catalogo catalogo) {
        List<Cuenta> cuentas = catalogo.cuentas().stream()
                .filter(c -> Textos.palabrasClave(c).contains(palabra))
                .toList();
        return cuentas.size() == 1 ? cuentas.get(0) : null;
    }

    private static boolean esEfectivo(String palabra, Catalogo catalogo) {
        return catalogo.mediosPago().stream()
                .filter(MedioPago::isEfectivo)
                .anyMatch(mp -> Textos.palabrasClave(mp).contains(palabra));
    }

    /** "arreglo de PC a" (antes de "mercado pago") → "arreglo de PC". */
    private static void sacarConectorFinal(List<String> resto) {
        while (!resto.isEmpty() && CONECTORES.contains(Textos.normalizar(resto.get(resto.size() - 1)))) {
            resto.remove(resto.size() - 1);
        }
    }

    private static String limpio(String token) {
        return token.replaceAll("[,;.!?]+$", "");
    }
}
