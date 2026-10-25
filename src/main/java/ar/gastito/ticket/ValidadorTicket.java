package ar.gastito.ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
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
import ar.gastito.movimiento.DatosTicket;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.MovimientoInterpretado;

/**
 * Controla en Java lo que leyó la IA (regla 2: la IA interpreta, no calcula):
 * <ul>
 * <li>El monto del gasto es el total impreso. Sin total no hay gasto: se pide otra foto.</li>
 * <li>Las cuentas del ticket se revisan con {@link ControlesTicket}; la tarjeta avisa las que no cierran y qué
 * líneas no se pudieron leer.</li>
 * <li>Los importes se aceptan con coma o con punto decimal, aunque se mezclen en el mismo ticket.</li>
 * <li>Los descuentos quedan en el orden impreso, con subtotal negativo.</li>
 * <li>El código de barras se guarda solo si es un EAN-8 o EAN-13 válido.</li>
 * <li>La fecha es la del ticket. Una fecha futura o de hace más de un año se toma como mal leída: queda hoy.</li>
 * <li>Categoría y cuenta se buscan en el catálogo, igual que con un texto. El medio de pago, solo por nombre
 * exacto: "Tarjeta" a secas no se toma como crédito.</li>
 * <li>Privacidad: la respuesta de la IA solo tiene campos para lo que se guarda. En los textos libres
 * (descripciones, comercio, líneas ilegibles) se borran además las tarjetas y los CUIT que se hayan colado.</li>
 * </ul>
 */
@Component
public class ValidadorTicket {

    static final int LARGO_MAXIMO_DESCRIPCION = 255;
    static final int LARGO_MAXIMO_COMERCIO = 100;
    static final int LARGO_MAXIMO_SUCURSAL = 20;
    static final int LARGO_MAXIMO_LINEA_ILEGIBLE = 200;
    static final int MAXIMO_LINEAS_ILEGIBLES = 10;
    static final int MAXIMO_ITEMS = 200;
    static final int DIAS_MAXIMOS_ATRAS = 365;

    /** Una tarjeta enmascarada: "**** 1234", "XXXX-1234" (pero no el talle "XXL"). */
    private static final Pattern TARJETA_ENMASCARADA =
            Pattern.compile("(?:[*#]{2,}|[xX]{4})[\\s\\-*#xX]*\\d{2,4}\\b");
    /** Un número de tarjeta completo, en grupos: "4509 9535 6623 3704", "3714-496353-98431". */
    private static final Pattern TARJETA_COMPLETA =
            Pattern.compile("\\b\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{1,4}\\b|\\b\\d{4}[ -]\\d{6}[ -]\\d{5}\\b");
    /** Un CUIT o CUIL: "30-68731043-4", "20 12345678 9" (los once dígitos juntos, también). */
    private static final Pattern CUIT =
            Pattern.compile("\\b(?:20|23|24|27|30|33|34)[-\\s.]?\\d{8}[-\\s.]?\\d\\b");

    /** Lo que dice si una tarjeta es de débito o de crédito (o su marca, que en el catálogo es crédito). */
    private static final Pattern ACLARA_LA_TARJETA = Pattern.compile(
            "DEBITO|CREDITO|VISA|MASTER|MAESTRO|ELECTRON|AMEX|AMERICAN|NARANJA|CABAL|DINERS|NATIVA|ARGENCARD");

    /** La alícuota de IVA entre paréntesis: "(21,00)", "(10,50)", "(21%)". */
    private static final Pattern ALICUOTA_IVA = Pattern.compile("\\(\\s*\\d{1,2}(?:[.,]\\d{1,2})?\\s*%?\\s*\\)");

    /** La hora al final de la fecha: "22-09-2026 17:44:28". */
    private static final Pattern HORA_PEGADA = Pattern.compile("\\s(\\d{1,2}:\\d{2}(?::\\d{2})?)$");

    private final ResolvedorFechas fechas;

    public ValidadorTicket(ResolvedorFechas fechas) {
        this.fechas = fechas;
    }

    public sealed interface Resultado {
    }

    /**
     * @param ticket       lo propio del ticket: comercio, sucursal, subtotal y descuentos impresos, productos
     * @param diferencias  los controles que no cierran; vacía si las cuentas dan
     */
    public record Valido(MovimientoInterpretado movimiento, Confianza confianza, DatosTicket ticket,
            List<ControlesTicket.Diferencia> diferencias) implements Resultado {

        public boolean coincide() {
            return diferencias.isEmpty();
        }

        public String comercio() {
            return ticket.comercio();
        }

        public List<ItemTicket> items() {
            return ticket.items();
        }

        /** Productos menos descuentos, o null si no se leyó ningún renglón. */
        public BigDecimal sumaItems() {
            return ticket.items().isEmpty() ? null
                    : ticket.items().stream().map(ItemTicket::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
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

    /** Sin texto del usuario junto a la foto. */
    public Resultado validar(LecturaTicketIa ia, Catalogo catalogo, LocalDateTime ahora) {
        return validar(ia, catalogo, ahora, false);
    }

    /**
     * @param conTextoDelUsuario si el usuario escribió algo con la foto: lo que dice manda sobre lo impreso, así
     *                           que una tarjeta sin aclarar en el ticket no anula el medio que eligió la IA
     */
    public Resultado validar(LecturaTicketIa ia, Catalogo catalogo, LocalDateTime ahora, boolean conTextoDelUsuario) {
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
        Optional<BigDecimal> total = Importes.importe(ia.total()).map(BigDecimal::abs).filter(t -> t.signum() > 0);
        if (total.isEmpty()) {
            // Cortada y sin total: falta justo lo que importa.
            return new Ilegible(problema.contains("cortada") ? Motivo.CORTADA : Motivo.SIN_TOTAL);
        }

        List<ItemTicket> items = items(ia);
        BigDecimal subtotalImpreso = Importes.importe(ia.subtotal()).map(BigDecimal::abs)
                .filter(s -> s.signum() > 0).orElse(null);
        BigDecimal descuentosImpresos = Importes.importe(ia.descuentos()).map(d -> d.abs().negate()).orElse(null);
        List<ControlesTicket.Diferencia> diferencias = ControlesTicket.revisar(
                items.stream().map(ItemTicket::subtotal).toList(), subtotalImpreso, descuentosImpresos, total.get());

        ValidadorInterpretacion.Candidatas candidatas =
                ValidadorInterpretacion.candidatas(ia.categorias(), ia.confianza(), catalogo);
        Categoria primera = candidatas.categorias().get(0);

        // Solo el nombre exacto: un ticket que dice "TARJETA" puede ser débito o crédito, y la palabra clave
        // "tarjeta" lo llevaría a crédito. Sin aclarar, queda sin medio de pago y lo elige el usuario.
        MedioPago medio = ValidadorInterpretacion.buscarPorNombre(ia.medioPago(), catalogo.mediosPago()).orElse(null);
        // Aunque el prompt lo pide, con el ticket real la IA eligió crédito para un "TARJETA" a secas: lo decide Java.
        if (!conTextoDelUsuario && tarjetaSinAclarar(ia.medioPagoImpreso())) {
            medio = null;
        }
        Cuenta cuenta = ValidadorInterpretacion.buscar(ia.cuenta(), catalogo.cuentas()).orElse(null);
        TarjetaCredito tarjeta = null;
        if (medio != null && medio.esTarjetaDeCredito()) {
            tarjeta = catalogo.tarjetaCredito().orElse(null);
            cuenta = null; // la cuenta es la que paga el resumen
        }

        String comercio = recortar(capitalizar(limpiar(ia.comercio())), LARGO_MAXIMO_COMERCIO);
        String descripcion = comercio == null ? primera.getNombre() : comercio;
        LocalDateTime fechaHora = fechaHora(ia.fecha(), ia.hora(), ahora);

        MovimientoInterpretado movimiento = candidatas.confianza() == Confianza.ALTA
                ? MovimientoInterpretado.conCategoria(total.get(), descripcion, primera, medio, cuenta, tarjeta,
                        fechaHora)
                : new MovimientoInterpretado(total.get(), descripcion, null, candidatas.categorias(), medio, cuenta,
                        tarjeta, fechaHora);
        DatosTicket ticket = new DatosTicket(comercio, sucursal(ia.sucursal()), subtotalImpreso, descuentosImpresos,
                lineasIlegibles(ia.lineasIlegibles()), items);
        return new Valido(movimiento, candidatas.confianza(), ticket, diferencias);
    }

    /**
     * Los renglones en el orden impreso. Un descuento queda en negativo (aunque la IA lo mande en positivo); un
     * renglón en negativo sin la marca también se toma como descuento. Lo que no tiene importe se descarta.
     */
    static List<ItemTicket> items(LecturaTicketIa ia) {
        List<ItemTicket> items = new ArrayList<>();
        for (LecturaTicketIa.Item item : ia.items() == null ? List.<LecturaTicketIa.Item>of() : ia.items()) {
            if (item == null || items.size() >= MAXIMO_ITEMS) {
                continue;
            }
            Optional<BigDecimal> cantidad = Importes.cantidad(item.cantidad());
            Optional<BigDecimal> precio = Importes.importe(item.precioUnitario()).map(BigDecimal::abs)
                    .filter(p -> p.signum() > 0);
            Optional<BigDecimal> leido = Importes.importe(item.subtotal())
                    .or(() -> cantidad.isPresent() && precio.isPresent()
                            ? Optional.of(cantidad.get().multiply(precio.get()).setScale(2, RoundingMode.HALF_UP))
                            : Optional.empty());
            if (leido.isEmpty() || leido.get().signum() == 0) {
                continue;
            }
            boolean descuento = item.esDescuento() || leido.get().signum() < 0;
            BigDecimal subtotal = descuento ? leido.get().abs().negate() : leido.get();
            String descripcion = descripcion(sinAlicuota(item.descripcion()), descuento ? "Descuento" : "Producto");
            String original = recortar(limpiar(sinAlicuota(item.descripcionOriginal())), LARGO_MAXIMO_DESCRIPCION);
            String codigo = descuento ? null : CodigoBarras.validar(item.codigoBarras()).orElse(null);
            items.add(new ItemTicket(descripcion, original, codigo, cantidad.orElse(null), precio.orElse(null),
                    subtotal));
        }
        return items;
    }

    /**
     * La fecha puede venir como dd-mm-aaaa, dd/mm/aaaa o dd/mm/aa. Si la IA copia la hora pegada a la fecha
     * ("22-09-2026 17:44:28") y no la pone aparte, se usa esa.
     */
    private LocalDateTime fechaHora(String fecha, String hora, LocalDateTime ahora) {
        if ((hora == null || hora.isBlank()) && fecha != null) {
            Matcher pegada = HORA_PEGADA.matcher(fecha.strip());
            if (pegada.find()) {
                hora = pegada.group(1);
            }
        }
        LocalDateTime resuelta = fechas.resolver(fecha, hora, ahora);
        LocalDateTime minimo = ahora.toLocalDate().minusDays(DIAS_MAXIMOS_ATRAS).atStartOfDay();
        if (resuelta.isAfter(ahora) || resuelta.isBefore(minimo)) {
            return ahora.truncatedTo(ChronoUnit.MINUTES);
        }
        return resuelta;
    }

    /**
     * "TARJETA" impreso sin decir si es débito o crédito ni la marca: no se puede saber cuál de las dos fue.
     * "TARJETA DEBITO", "VISA", "MASTERCARD"… sí se pueden saber.
     */
    static boolean tarjetaSinAclarar(String impreso) {
        if (impreso == null) {
            return false;
        }
        String t = Normalizer.normalize(impreso, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        return t.contains("TARJETA") && !ACLARA_LA_TARJETA.matcher(t).find();
    }

    // --- Textos ---

    /**
     * Un texto libre sin tarjetas (enmascaradas o completas) ni CUIT, y sin espacios de más. Null si no queda
     * nada. No borra números por su largo: un código de barras o un peso no son datos personales.
     */
    static String limpiar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = TARJETA_ENMASCARADA.matcher(texto).replaceAll(" ");
        limpio = TARJETA_COMPLETA.matcher(limpio).replaceAll(" ");
        limpio = CUIT.matcher(limpio).replaceAll(" ");
        limpio = limpio.replaceAll("\\s+", " ").strip().replaceAll("^[\\s\\-:#*.,]+|[\\s\\-:#*,]+$", "");
        return limpio.isEmpty() ? null : limpio;
    }

    /** Sin la alícuota de IVA que Día imprime junto a cada producto: "GALLET CRACKER QSO (21,00)". */
    static String sinAlicuota(String texto) {
        return texto == null ? null : ALICUOTA_IVA.matcher(texto).replaceAll(" ");
    }

    private static String descripcion(String texto, String porDefecto) {
        String limpio = recortar(capitalizar(limpiar(texto)), LARGO_MAXIMO_DESCRIPCION);
        return limpio == null ? porDefecto : limpio;
    }

    /** "5601": solo letras, números, espacios y guiones. */
    private static String sucursal(String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.replaceAll("[^\\p{L}\\p{N} -]", "").replaceAll("\\s+", " ").strip();
        return limpio.isEmpty() ? null : recortar(limpio, LARGO_MAXIMO_SUCURSAL);
    }

    private static List<String> lineasIlegibles(List<String> lineas) {
        if (lineas == null) {
            return List.of();
        }
        return lineas.stream()
                .map(ValidadorTicket::limpiar)
                .filter(l -> l != null)
                .map(l -> recortar(capitalizar(l), LARGO_MAXIMO_LINEA_ILEGIBLE))
                .limit(MAXIMO_LINEAS_ILEGIBLES)
                .toList();
    }

    private static String capitalizar(String texto) {
        return texto == null ? null : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    private static String recortar(String texto, int largo) {
        return texto == null || texto.length() <= largo ? texto : texto.substring(0, largo).strip();
    }
}
