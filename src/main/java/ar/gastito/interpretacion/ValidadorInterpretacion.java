package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.ConPalabrasClave;
import ar.gastito.movimiento.Cuenta;
import ar.gastito.movimiento.MedioPago;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.Tarjeta;

/**
 * Controla la respuesta de la IA contra la base antes de mostrarla:
 * <ul>
 * <li>Categoría que no existe: Otros.</li>
 * <li>Medio de pago o cuenta que no existen: vacío.</li>
 * <li>Pago con tarjeta de crédito: se asocia la tarjeta y no la cuenta (la cuenta paga el resumen).</li>
 * <li>Monto ausente, inválido o no positivo: hay que preguntarlo.</li>
 * </ul>
 */
@Component
public class ValidadorInterpretacion {

    static final int LARGO_MAXIMO_DESCRIPCION = 255;

    private final ResolvedorFechas fechas;

    public ValidadorInterpretacion(ResolvedorFechas fechas) {
        this.fechas = fechas;
    }

    public sealed interface Resultado {
    }

    public record Valida(MovimientoInterpretado movimiento) implements Resultado {
    }

    /** Todo bien salvo el monto: el movimiento viene con monto null. */
    public record FaltaMonto(MovimientoInterpretado sinMonto) implements Resultado {
    }

    public record NoEsGasto() implements Resultado {
    }

    public Resultado validar(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        if (ia == null || !ia.esGasto()) {
            return new NoEsGasto();
        }
        Categoria categoria = buscar(ia.categoria(), catalogo.categorias()).orElseGet(catalogo::otros);
        MedioPago medio = buscar(ia.medioPago(), catalogo.mediosPago()).orElse(null);
        Cuenta cuenta = buscar(ia.cuenta(), catalogo.cuentas()).orElse(null);
        Tarjeta tarjeta = null;
        if (medio != null && medio.esTarjetaDeCredito()) {
            tarjeta = catalogo.tarjetaCredito().orElse(null);
            cuenta = null;
        }
        String descripcion = descripcion(ia.descripcion(), categoria);
        LocalDateTime fechaHora = fechas.resolver(ia.fecha(), ia.hora(), ahora);

        MovimientoInterpretado movimiento = new MovimientoInterpretado(monto(ia.monto()).orElse(null), descripcion,
                categoria, medio, cuenta, tarjeta, fechaHora);
        return movimiento.monto() == null ? new FaltaMonto(movimiento) : new Valida(movimiento);
    }

    static Optional<BigDecimal> monto(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String limpio = texto.trim().replace("$", "").replace(" ", "");
        Optional<BigDecimal> monto = Montos.parsear(limpio);
        if (monto.isEmpty()) {
            try {
                monto = Optional.of(new BigDecimal(limpio));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return monto.filter(m -> m.signum() > 0).map(m -> m.setScale(2, RoundingMode.HALF_UP));
    }

    private static String descripcion(String texto, Categoria categoria) {
        if (texto == null || texto.isBlank()) {
            return categoria.getNombre();
        }
        String d = Textos.capitalizar(texto.trim());
        return d.length() > LARGO_MAXIMO_DESCRIPCION ? d.substring(0, LARGO_MAXIMO_DESCRIPCION) : d;
    }

    /** Primero por nombre exacto (sin tildes ni mayúsculas), después por palabra clave. */
    private static <T extends ConPalabrasClave> Optional<T> buscar(String nombre, List<T> opciones) {
        if (nombre == null || nombre.isBlank()) {
            return Optional.empty();
        }
        String buscado = Textos.normalizar(nombre);
        Optional<T> exacto = opciones.stream()
                .filter(o -> Textos.normalizar(o.getNombre()).equals(buscado))
                .findFirst();
        if (exacto.isPresent()) {
            return exacto;
        }
        List<T> porPalabra = opciones.stream()
                .filter(o -> Textos.palabrasClave(o).contains(buscado))
                .toList();
        return porPalabra.size() == 1 ? Optional.of(porPalabra.get(0)) : Optional.empty();
    }
}
