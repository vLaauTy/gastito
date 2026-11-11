package ar.gastito.catalogo;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cómo se pagó de contado, sacado del texto sin IA: "con débito" → Débito, "transferí" → Transferencia,
 * "en efectivo" → Efectivo, "con QR" → QR, "con mercado pago" → Mercado Pago. Es solo un detalle: para las cuentas,
 * efectivo, débito, transferencia y QR son lo mismo (Contado). Con "tarjeta" o "crédito" (salvo "tarjeta de
 * débito") no es contado y no hay detalle.
 */
public final class DetallePago {

    public static final String EFECTIVO = "Efectivo";
    public static final String DEBITO = "Débito";
    public static final String TRANSFERENCIA = "Transferencia";
    public static final String QR = "QR";
    public static final String MERCADO_PAGO = "Mercado Pago";

    /** El largo de la columna. */
    public static final int LARGO_MAXIMO = 40;

    private static final Pattern CREDITO = Pattern.compile("\\b(tarjeta|credito)\\b");
    private static final Pattern DEBITO_P = Pattern.compile("\\b(debito|tarjeta de debito)\\b");
    private static final Pattern TRANSFERENCIA_P = Pattern.compile("\\b(transferencia|transferi|transfer|transf)\\b");
    private static final Pattern EFECTIVO_P = Pattern.compile("\\b(efectivo|cash|billete|billetes)\\b");
    private static final Pattern QR_P = Pattern.compile("\\bqr\\b");
    private static final Pattern MERCADO_PAGO_P = Pattern.compile("\\b(mercado ?pago|mp)\\b");

    private DetallePago() {
    }

    /** El detalle que dice el texto, o null si no dice ninguno (o dice que fue con crédito). */
    public static String reconocer(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String t = normalizar(texto);
        if (DEBITO_P.matcher(t).find()) {
            return DEBITO;
        }
        if (CREDITO.matcher(t).find()) {
            return null;
        }
        if (TRANSFERENCIA_P.matcher(t).find()) {
            return TRANSFERENCIA;
        }
        if (QR_P.matcher(t).find()) {
            return QR;
        }
        if (EFECTIVO_P.matcher(t).find()) {
            return EFECTIVO;
        }
        if (MERCADO_PAGO_P.matcher(t).find()) {
            return MERCADO_PAGO;
        }
        return null;
    }

    /** Un detalle escrito a mano (el dashboard): sin espacios de más, vacío es null, y cortado al largo. */
    public static String limpiar(String detalle) {
        if (detalle == null || detalle.isBlank()) {
            return null;
        }
        String d = detalle.strip().replaceAll("\\s+", " ");
        return d.length() > LARGO_MAXIMO ? d.substring(0, LARGO_MAXIMO) : d;
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").strip();
    }
}
