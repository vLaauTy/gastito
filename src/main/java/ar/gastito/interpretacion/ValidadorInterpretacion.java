package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.MovimientoInterpretado;

/**
 * Controla la respuesta de la IA contra la base antes de mostrarla:
 * <ul>
 * <li>Candidatas: se descartan las que no existen y las repetidas, hasta {@value #MAXIMO_CANDIDATAS}.
 * Si no queda ninguna, Otros con confianza baja.</li>
 * <li>Solo con confianza alta (y si la primera candidata existe) la categoría queda elegida; si no, se pregunta.</li>
 * <li>Medio de pago o cuenta que no existen: vacío.</li>
 * <li>Pago con tarjeta de crédito: se asocia la tarjeta y no la cuenta (la cuenta paga el resumen).</li>
 * <li>Monto ausente, inválido o no positivo: queda vacío y se pide en la tarjeta.</li>
 * </ul>
 */
@Component
public class ValidadorInterpretacion {

    static final int LARGO_MAXIMO_DESCRIPCION = 255;
    static final int MAXIMO_CANDIDATAS = 3;

    private final ResolvedorFechas fechas;

    public ValidadorInterpretacion(ResolvedorFechas fechas) {
        this.fechas = fechas;
    }

    public sealed interface Resultado {
    }

    /** El movimiento puede venir sin monto o sin categoría elegida: se completa en la tarjeta. */
    public record Valida(MovimientoInterpretado movimiento, Confianza confianza) implements Resultado {
    }

    public record NoEsGasto() implements Resultado {
    }

    /** Una pregunta sobre sus gastos: la responde ConsultaService. */
    public record Pregunta() implements Resultado {
    }

    /** Puede ser un gasto o una pregunta: se le pregunta al usuario. Si dice que es un gasto, se usa este. */
    public record Dudosa(Valida comoGasto) implements Resultado {
    }

    public Resultado validar(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        Intencion intencion = ia == null ? Intencion.OTRO : Intencion.de(ia.intencion());
        return switch (intencion) {
            case OTRO -> new NoEsGasto();
            case PREGUNTA -> new Pregunta();
            case GASTO -> comoGasto(ia, catalogo, ahora);
            case DUDOSO -> new Dudosa(comoGasto(ia, catalogo, ahora));
        };
    }

    private Valida comoGasto(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        Candidatas c = candidatas(ia.categorias(), ia.confianza(), catalogo);
        List<Categoria> candidatas = c.categorias();
        Confianza confianza = c.confianza();

        MedioPago medio = buscar(ia.medioPago(), catalogo.mediosPago()).orElse(null);
        Cuenta cuenta = buscar(ia.cuenta(), catalogo.cuentas()).orElse(null);
        TarjetaCredito tarjeta = null;
        if (medio != null && medio.esTarjetaDeCredito()) {
            tarjeta = catalogo.tarjetaCredito().orElse(null);
            cuenta = null;
        }
        String descripcion = descripcion(ia.descripcion(), candidatas.get(0));
        LocalDateTime fechaHora = fechas.resolver(ia.fecha(), ia.hora(), ahora);
        BigDecimal monto = monto(ia.monto()).orElse(null);

        MovimientoInterpretado movimiento = confianza == Confianza.ALTA
                ? MovimientoInterpretado.conCategoria(monto, descripcion, candidatas.get(0), medio, cuenta, tarjeta,
                        fechaHora)
                : new MovimientoInterpretado(monto, descripcion, null, candidatas, medio, cuenta, tarjeta, fechaHora);
        return new Valida(movimiento, confianza);
    }

    /**
     * Las categorías que propuso la IA que existen, sin repetir y en orden (al menos una).
     *
     * @param confianza ya corregida: baja si no quedó ninguna o si la primera propuesta no existe
     */
    public record Candidatas(List<Categoria> categorias, Confianza confianza) {
    }

    public static Candidatas candidatas(List<String> nombres, String confianzaIa, Catalogo catalogo) {
        List<Categoria> candidatas = new ArrayList<>();
        boolean primeraExiste = false;
        List<String> propuestas = nombres == null ? List.of() : nombres;
        for (int i = 0; i < propuestas.size() && candidatas.size() < MAXIMO_CANDIDATAS; i++) {
            Optional<Categoria> c = buscar(propuestas.get(i), catalogo.categorias());
            if (i == 0) {
                primeraExiste = c.isPresent();
            }
            c.filter(categoria -> !candidatas.contains(categoria)).ifPresent(candidatas::add);
        }
        Confianza confianza = confianza(confianzaIa);
        if (candidatas.isEmpty()) {
            candidatas.add(catalogo.otros());
            confianza = Confianza.BAJA;
        } else if (confianza == Confianza.ALTA && !primeraExiste) {
            confianza = Confianza.BAJA;
        }
        return new Candidatas(List.copyOf(candidatas), confianza);
    }

    static Confianza confianza(String texto) {
        if (texto == null) {
            return Confianza.BAJA;
        }
        return switch (Textos.normalizar(texto)) {
            case "alta" -> Confianza.ALTA;
            case "media" -> Confianza.MEDIA;
            default -> Confianza.BAJA;
        };
    }

    /** Un importe en pesos: "1500", "1500.50" o a la argentina ("1.500,50"). Vacío si no es positivo. */
    public static Optional<BigDecimal> monto(String texto) {
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

    /** Solo por nombre exacto (sin tildes ni mayúsculas), sin palabras clave. */
    public static <T extends ConPalabrasClave> Optional<T> buscarPorNombre(String nombre, List<T> opciones) {
        if (nombre == null || nombre.isBlank()) {
            return Optional.empty();
        }
        String buscado = Textos.normalizar(nombre);
        return opciones.stream().filter(o -> Textos.normalizar(o.getNombre()).equals(buscado)).findFirst();
    }

    /** Primero por nombre exacto (sin tildes ni mayúsculas), después por palabra clave. */
    public static <T extends ConPalabrasClave> Optional<T> buscar(String nombre, List<T> opciones) {
        Optional<T> exacto = buscarPorNombre(nombre, opciones);
        if (exacto.isPresent() || nombre == null || nombre.isBlank()) {
            return exacto;
        }
        String buscado = Textos.normalizar(nombre);
        List<T> porPalabra = opciones.stream()
                .filter(o -> Textos.palabrasClave(o).contains(buscado))
                .toList();
        return porPalabra.size() == 1 ? Optional.of(porPalabra.get(0)) : Optional.empty();
    }
}
