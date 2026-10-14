package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.ConPalabrasClave;
import ar.gastito.movimiento.Cuenta;
import ar.gastito.movimiento.MedioPago;

/**
 * Interpreta sin IA los mensajes obvios: un monto y una palabra clave de categoría, en cualquier orden
 * ("kiosco 1500", "1500 kiosco"), opcionalmente con una palabra de medio de pago y una de cuenta.
 * Si sobra cualquier otra palabra o algo es ambiguo, devuelve vacío y el mensaje pasa a la IA.
 */
@Component
public class ParserSimple {

    public record Resultado(BigDecimal monto, String descripcion, Categoria categoria, MedioPago medioPago,
            Cuenta cuenta) {
    }

    public Optional<Resultado> parsear(String texto, Catalogo catalogo) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        BigDecimal monto = null;
        String palabraCategoria = null;
        Categoria categoria = null;
        MedioPago medio = null;
        Cuenta cuenta = null;

        for (String token : texto.trim().split("\\s+")) {
            String limpio = token.replaceAll("[,;.!?]+$", "");
            Optional<BigDecimal> comoMonto = Montos.parsear(limpio);
            if (comoMonto.isPresent()) {
                if (monto != null) {
                    return Optional.empty(); // dos montos: que lo resuelva la IA
                }
                monto = comoMonto.get();
                continue;
            }
            String palabra = Textos.normalizar(limpio);
            List<Categoria> categorias = coincidencias(palabra, catalogo.categorias());
            List<MedioPago> medios = coincidencias(palabra, catalogo.mediosPago());
            List<Cuenta> cuentas = coincidencias(palabra, catalogo.cuentas());
            int tipos = (categorias.isEmpty() ? 0 : 1) + (medios.isEmpty() ? 0 : 1) + (cuentas.isEmpty() ? 0 : 1);
            if (tipos != 1 || categorias.size() > 1 || medios.size() > 1 || cuentas.size() > 1) {
                return Optional.empty(); // palabra desconocida o ambigua
            }
            if (!categorias.isEmpty() && categoria == null) {
                categoria = categorias.get(0);
                palabraCategoria = palabra;
            } else if (!medios.isEmpty() && medio == null) {
                medio = medios.get(0);
            } else if (!cuentas.isEmpty() && cuenta == null) {
                cuenta = cuentas.get(0);
            } else {
                return Optional.empty(); // dos categorías, dos medios o dos cuentas
            }
        }

        if (monto == null || categoria == null) {
            return Optional.empty();
        }
        return Optional.of(new Resultado(monto, Textos.capitalizar(palabraCategoria), categoria, medio, cuenta));
    }

    /**
     * Un mensaje separado en sus partes reconocibles y el resto, que se compara con el historial.
     *
     * @param fecha "hoy", "ayer" o "anteayer" si el mensaje lo dice; si no, vacío
     */
    public record Separado(BigDecimal monto, String resto, MedioPago medioPago, Cuenta cuenta, String fecha) {
    }

    private static final List<String> FECHAS_SIMPLES = List.of("hoy", "ayer", "anteayer");

    /**
     * Saca del mensaje un monto (exactamente uno), un medio de pago, una cuenta y una fecha simple; todo lo demás
     * queda como resto. Vacío si no hay monto, hay más de uno o no queda resto.
     * Ejemplo: "3700 en pan de pancho y papas, efectivo" → 3700, "en pan de pancho y papas", Efectivo.
     */
    public Optional<Separado> separar(String texto, Catalogo catalogo) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        BigDecimal monto = null;
        MedioPago medio = null;
        Cuenta cuenta = null;
        String fecha = "";
        List<String> resto = new ArrayList<>();

        for (String token : texto.trim().split("\\s+")) {
            String limpio = token.replaceAll("[,;.!?]+$", "");
            Optional<BigDecimal> comoMonto = Montos.parsear(limpio);
            if (comoMonto.isPresent()) {
                if (monto != null) {
                    return Optional.empty();
                }
                monto = comoMonto.get();
                continue;
            }
            String palabra = Textos.normalizar(limpio);
            List<MedioPago> medios = coincidencias(palabra, catalogo.mediosPago());
            List<Cuenta> cuentas = coincidencias(palabra, catalogo.cuentas());
            if (medio == null && medios.size() == 1 && cuentas.isEmpty()) {
                medio = medios.get(0);
            } else if (cuenta == null && cuentas.size() == 1 && medios.isEmpty()) {
                cuenta = cuentas.get(0);
            } else if (fecha.isEmpty() && FECHAS_SIMPLES.contains(palabra)) {
                fecha = palabra;
            } else if (!limpio.isEmpty()) {
                resto.add(limpio);
            }
        }
        if (monto == null || resto.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Separado(monto, String.join(" ", resto), medio, cuenta, fecha));
    }

    private static <T extends ConPalabrasClave> List<T> coincidencias(String palabra, List<T> opciones) {
        List<T> resultado = new ArrayList<>();
        for (T opcion : opciones) {
            if (Textos.palabrasClave(opcion).contains(palabra)) {
                resultado.add(opcion);
            }
        }
        return resultado;
    }
}
