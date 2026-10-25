package ar.gastito.ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.MovimientoInterpretado;

/**
 * Controla en Java lo que leyó la IA (regla 2: la IA interpreta, no calcula):
 * <ul>
 * <li>El monto del gasto es el total impreso. Sin total no hay gasto: se pide otra foto.</li>
 * <li>Los productos menos los descuentos se comparan con el total ({@link #coinciden}); la tarjeta avisa si no.</li>
 * <li>La fecha es la del ticket. Una fecha futura o de hace más de un año se toma como mal leída: queda hoy.</li>
 * <li>Categoría y cuenta se buscan en el catálogo, igual que con un texto. El medio de pago, solo por nombre
 * exacto: "Tarjeta" a secas no se toma como crédito.</li>
 * <li>Privacidad: de los textos se borra cualquier número largo (tarjeta, CUIT, códigos) y las tarjetas
 * enmascaradas ("**** 1234").</li>
 * </ul>
 */
@Component
public class ValidadorTicket {

    /** Diferencia aceptada entre los productos y el total (redondeos, centavos que no se leen). */
    public static final BigDecimal TOLERANCIA = new BigDecimal("10");
    static final int LARGO_MAXIMO_DESCRIPCION = 255;
    static final int LARGO_MAXIMO_COMERCIO = 100;
    static final int MAXIMO_ITEMS = 200;
    static final int DIAS_MAXIMOS_ATRAS = 365;

    /** Seis o más dígitos, aunque estén separados por espacios, puntos o guiones. */
    private static final Pattern NUMERO_LARGO = Pattern.compile("(?:\\d[\\s.\\-/]?){5,}\\d");
    /** Una tarjeta enmascarada: "**** 1234", "XXXX-1234" (pero no el talle "XXL"). */
    private static final Pattern TARJETA_ENMASCARADA =
            Pattern.compile("(?:[*#]{2,}|[xX]{4})[\\s\\-*#xX]*\\d{2,4}\\b");

    private final ResolvedorFechas fechas;

    public ValidadorTicket(ResolvedorFechas fechas) {
        this.fechas = fechas;
    }

    public sealed interface Resultado {
    }

    /**
     * @param comercio   como lo leyó la IA, ya limpio (puede ser null)
     * @param items      los productos y, al final, los descuentos (con subtotal negativo)
     * @param sumaItems  la suma de todos los renglones, o null si no se leyó ninguno
     */
    public record Valido(MovimientoInterpretado movimiento, Confianza confianza, String comercio,
            List<ItemTicket> items, BigDecimal sumaItems) implements Resultado {

        /** Sin productos no hay con qué comparar: se toma el total como bueno. */
        public boolean coincide() {
            return sumaItems == null || coinciden(sumaItems, movimiento.monto());
        }
    }

    public record NoEsTicket() implements Resultado {
    }

    public record Ilegible(Motivo motivo) implements Resultado {
    }

    public enum Motivo {
        BORROSA,
        CORTADA,
        SIN_TOTAL
    }

    /** Los productos (menos los descuentos) y el total difieren en hasta {@link #TOLERANCIA}. */
    public static boolean coinciden(BigDecimal sumaItems, BigDecimal total) {
        return sumaItems.subtract(total).abs().compareTo(TOLERANCIA) <= 0;
    }

    public Resultado validar(LecturaTicketIa ia, Catalogo catalogo, LocalDateTime ahora) {
        if (ia == null) {
            return new Ilegible(Motivo.BORROSA);
        }
        String problema = ia.problema() == null ? "" : ia.problema().strip().toLowerCase(Locale.ROOT);
        if (problema.contains("no_es_ticket") || problema.contains("no es")) {
            return new NoEsTicket();
        }
        if (problema.contains("borrosa")) {
            return new Ilegible(Motivo.BORROSA);
        }
        Optional<BigDecimal> total = ValidadorInterpretacion.monto(ia.total());
        if (total.isEmpty()) {
            // Cortada y sin total: falta justo lo que importa.
            return new Ilegible(problema.contains("cortada") ? Motivo.CORTADA : Motivo.SIN_TOTAL);
        }

        List<ItemTicket> items = items(ia);
        BigDecimal suma = items.isEmpty() ? null
                : items.stream().map(ItemTicket::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        ValidadorInterpretacion.Candidatas candidatas =
                ValidadorInterpretacion.candidatas(ia.categorias(), ia.confianza(), catalogo);
        Categoria primera = candidatas.categorias().get(0);

        // Solo el nombre exacto: un ticket que dice "TARJETA" puede ser débito o crédito, y la palabra clave
        // "tarjeta" lo llevaría a crédito. Sin aclarar, queda sin medio de pago y lo elige el usuario.
        MedioPago medio = ValidadorInterpretacion.buscarPorNombre(ia.medioPago(), catalogo.mediosPago()).orElse(null);
        Cuenta cuenta = ValidadorInterpretacion.buscar(ia.cuenta(), catalogo.cuentas()).orElse(null);
        TarjetaCredito tarjeta = null;
        if (medio != null && medio.esTarjetaDeCredito()) {
            tarjeta = catalogo.tarjetaCredito().orElse(null);
            cuenta = null; // la cuenta es la que paga el resumen
        }

        String comercio = recortar(limpiar(ia.comercio()), LARGO_MAXIMO_COMERCIO);
        String descripcion = comercio == null ? primera.getNombre() : comercio;
        LocalDateTime fechaHora = fechaHora(ia.fecha(), ia.hora(), ahora);

        MovimientoInterpretado movimiento = candidatas.confianza() == Confianza.ALTA
                ? MovimientoInterpretado.conCategoria(total.get(), descripcion, primera, medio, cuenta, tarjeta,
                        fechaHora)
                : new MovimientoInterpretado(total.get(), descripcion, null, candidatas.categorias(), medio, cuenta,
                        tarjeta, fechaHora);
        return new Valido(movimiento, candidatas.confianza(), comercio, items, suma);
    }

    /** Los productos en orden y después los descuentos, en negativo. Lo que no tiene importe se descarta. */
    static List<ItemTicket> items(LecturaTicketIa ia) {
        List<ItemTicket> items = new ArrayList<>();
        for (LecturaTicketIa.Item item : ia.items() == null ? List.<LecturaTicketIa.Item>of() : ia.items()) {
            if (item == null || items.size() >= MAXIMO_ITEMS) {
                continue;
            }
            Optional<BigDecimal> cantidad = decimal(item.cantidad());
            Optional<BigDecimal> precio = importe(item.precioUnitario());
            Optional<BigDecimal> subtotal = importeConSigno(item.subtotal())
                    .or(() -> cantidad.isPresent() && precio.isPresent()
                            ? Optional.of(cantidad.get().multiply(precio.get()).setScale(2, RoundingMode.HALF_UP))
                            : Optional.empty());
            if (subtotal.isEmpty() || subtotal.get().signum() == 0) {
                continue;
            }
            String descripcion = descripcion(item.descripcion(), subtotal.get().signum() < 0 ? "Descuento" : "Producto");
            items.add(new ItemTicket(descripcion, cantidad.orElse(null), precio.orElse(null), subtotal.get()));
        }
        for (LecturaTicketIa.Descuento d : ia.descuentos() == null ? List.<LecturaTicketIa.Descuento>of()
                : ia.descuentos()) {
            if (d == null || items.size() >= MAXIMO_ITEMS) {
                continue;
            }
            Optional<BigDecimal> monto = importeConSigno(d.monto()).map(BigDecimal::abs).filter(m -> m.signum() > 0);
            monto.ifPresent(m -> items.add(new ItemTicket(descripcion(d.descripcion(), "Descuento"), null, null,
                    m.negate())));
        }
        return items;
    }

    private LocalDateTime fechaHora(String fecha, String hora, LocalDateTime ahora) {
        LocalDateTime resuelta = fechas.resolver(fecha, hora, ahora);
        LocalDateTime minimo = ahora.toLocalDate().minusDays(DIAS_MAXIMOS_ATRAS).atStartOfDay();
        if (resuelta.isAfter(ahora) || resuelta.isBefore(minimo)) {
            return ahora.truncatedTo(ChronoUnit.MINUTES);
        }
        return resuelta;
    }

    // --- Textos e importes ---

    /** Sin números largos ni tarjetas enmascaradas, sin espacios de más. Null si no queda nada. */
    static String limpiar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = TARJETA_ENMASCARADA.matcher(texto).replaceAll(" ");
        limpio = NUMERO_LARGO.matcher(limpio).replaceAll(" ");
        limpio = limpio.replaceAll("\\s+", " ").strip().replaceAll("^[\\s\\-:#*.,]+|[\\s\\-:#*,]+$", "");
        if (limpio.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(limpio.charAt(0)) + limpio.substring(1);
    }

    private static String descripcion(String texto, String porDefecto) {
        String limpio = recortar(limpiar(texto), LARGO_MAXIMO_DESCRIPCION);
        return limpio == null ? porDefecto : limpio;
    }

    private static String recortar(String texto, int largo) {
        return texto == null || texto.length() <= largo ? texto : texto.substring(0, largo).strip();
    }

    /** Un importe positivo, con dos decimales. */
    private static Optional<BigDecimal> importe(String texto) {
        return ValidadorInterpretacion.monto(texto);
    }

    /** Un importe que puede venir con signo menos (un descuento impreso como "-250"). */
    private static Optional<BigDecimal> importeConSigno(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String t = texto.strip();
        boolean negativo = t.startsWith("-") || t.endsWith("-");
        return importe(t.replace("-", "")).map(m -> negativo ? m.negate() : m);
    }

    /** Una cantidad o un peso positivos, con hasta tres decimales. */
    private static Optional<BigDecimal> decimal(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal valor = new BigDecimal(texto.strip().replace(',', '.'));
            return valor.signum() > 0 && valor.compareTo(new BigDecimal("9999999")) < 0
                    ? Optional.of(valor.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros())
                    : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
