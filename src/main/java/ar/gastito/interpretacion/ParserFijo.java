package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;

/**
 * Lee lo que va después de /fijo, sin IA: "seguro 32900 tarjeta día 4", "luz 28.000 débito el 10 bimestral",
 * "netflix 12 mil tarjeta dia 7".
 * <ul>
 * <li>El día: "día 4", "dia 4" o "el 4" (del 1 al 31).</li>
 * <li>El monto: el primer número que no es el día ("32.900", "32 mil").</li>
 * <li>"bimestral" o "cada dos meses"; "variable" (la luz, el gas y el agua lo son aunque no se diga).</li>
 * <li>El medio de pago y la cuenta, por su nombre o sus palabras clave.</li>
 * <li>Lo que sobra es el nombre; la categoría sale de sus palabras clave, o se pregunta.</li>
 * </ul>
 */
@Component
public class ParserFijo {

    private static final Set<String> DIA = Set.of("dia", "el");
    private static final Set<String> MULTIPLICADORES = Set.of("mil", "k");
    private static final Set<String> CONECTORES = Set.of("a", "en", "de", "desde", "por", "con", "al", "la", "los",
            "cada", "mes", "meses", "todos", "un", "una", "fijo", "pago", "se", "cobra");

    public Optional<PedidoFijo> parsear(String texto, Catalogo catalogo) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        List<String> tokens = List.of(texto.strip().split("\\s+"));
        Integer dia = null;
        BigDecimal monto = null;
        boolean bimestral = false;
        boolean variable = false;
        MedioPago medio = null;
        Cuenta cuenta = null;
        List<String> nombre = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String original = tokens.get(i).replaceAll("[,;:.]+$", "");
            String palabra = Textos.normalizar(original);
            String siguiente = i + 1 < tokens.size() ? Textos.normalizar(tokens.get(i + 1)) : "";
            if (DIA.contains(palabra) && siguiente.matches("\\d{1,2}") && dia == null) {
                int n = Integer.parseInt(siguiente);
                if (n >= 1 && n <= 31) {
                    dia = n;
                    i++;
                    continue;
                }
            }
            if (palabra.equals("bimestral") || palabra.equals("bimestrales")) {
                bimestral = true;
                continue;
            }
            if (palabra.equals("dos") && siguiente.startsWith("mes")) {
                bimestral = true;
                i++;
                continue;
            }
            if (palabra.equals("variable")) {
                variable = true;
                continue;
            }
            if (monto == null) {
                Optional<BigDecimal> m = MULTIPLICADORES.contains(siguiente)
                        ? Montos.parsearConPalabras(original + " " + siguiente)
                        : Montos.parsearConPalabras(original);
                if (m.isPresent()) {
                    monto = m.get();
                    if (MULTIPLICADORES.contains(siguiente)) {
                        i++;
                    }
                    continue;
                }
            }
            Optional<MedioPago> esMedio = ValidadorInterpretacion.buscar(palabra, catalogo.mediosPago());
            if (medio == null && esMedio.isPresent()) {
                medio = esMedio.get();
                continue;
            }
            Optional<Cuenta> esCuenta = ValidadorInterpretacion.buscar(palabra, catalogo.cuentas());
            if (cuenta == null && esCuenta.isPresent()) {
                cuenta = esCuenta.get();
                continue;
            }
            if (!CONECTORES.contains(palabra) && !palabra.isEmpty()) {
                nombre.add(original);
            }
        }
        if (monto == null || nombre.isEmpty()) {
            return Optional.empty();
        }
        String nombreFijo = Textos.capitalizar(String.join(" ", nombre));
        TarjetaCredito tarjeta = null;
        if (medio != null && medio.esTarjetaDeCredito()) {
            tarjeta = catalogo.tarjetaCredito().orElse(null);
            cuenta = null;
        }
        Categoria categoria = nombre.stream()
                .map(w -> ValidadorInterpretacion.buscar(Textos.normalizar(w), catalogo.categorias()))
                .flatMap(Optional::stream).findFirst().orElse(null);
        return Optional.of(new PedidoFijo(nombreFijo, monto.setScale(2, RoundingMode.HALF_UP), categoria,
                List.of(), medio, cuenta, tarjeta, dia, bimestral, variable || PedidoFijo.esVariable(nombreFijo)));
    }
}
