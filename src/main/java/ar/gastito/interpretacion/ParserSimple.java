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
