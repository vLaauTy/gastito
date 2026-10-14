package ar.gastito.bot;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import ar.gastito.movimiento.Categoria;
import ar.gastito.movimiento.Cuenta;
import ar.gastito.movimiento.MedioPago;
import ar.gastito.movimiento.Movimiento;

/** Cómo se muestran montos, fechas y movimientos en los mensajes del bot. */
@Component
public class FormatoMensajes {

    static final String SEPARADOR = " – ";
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_SEMANA = DateTimeFormatter.ofPattern("EEE d/M", ES_AR);
    private static final DateTimeFormatter DIA_CON_ANIO = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", ES_AR);

    private final Clock clock;

    public FormatoMensajes(Clock clock) {
        this.clock = clock;
    }

    /** "$3.700", "$1.500,50". */
    public String pesos(BigDecimal monto) {
        BigDecimal m = monto.stripTrailingZeros();
        return "$" + formato(m.scale() > 0 ? "#,##0.00" : "#,##0").format(monto);
    }

    /** "US$ 0,000242": los costos de la IA son muy chicos, se muestran hasta 6 decimales. */
    public String dolares(BigDecimal monto) {
        return "US$ " + formato("#,##0.00####").format(monto);
    }

    /** "Hoy 16:42", "Ayer", "Ayer 21:30", "sáb 10/10", "20/12/2025". Las 12:00 de otro día no se muestran. */
    public String fecha(LocalDateTime fechaHora) {
        LocalDate hoy = LocalDate.now(clock);
        LocalDate dia = fechaHora.toLocalDate();
        if (dia.equals(hoy)) {
            return "Hoy " + HORA.format(fechaHora);
        }
        String texto;
        if (dia.equals(hoy.minusDays(1))) {
            texto = "Ayer";
        } else if (dia.getYear() == hoy.getYear()) {
            texto = DIA_SEMANA.format(dia);
        } else {
            texto = DIA_CON_ANIO.format(dia);
        }
        LocalTime hora = fechaHora.toLocalTime();
        return hora.equals(LocalTime.NOON) ? texto : texto + " " + HORA.format(hora);
    }

    /** "octubre 2026". */
    public String mes(YearMonth mes) {
        return MES.format(mes);
    }

    /** Una línea en HTML para listados: "🍬 Kiosco – $3.700 – Pan de pancho y papas – Efectivo – Hoy 16:42". */
    public String linea(Movimiento m) {
        Categoria categoria = m.getCategoria();
        List<String> partes = new ArrayList<>();
        partes.add(html(categoria == null ? "¿Qué categoría?" : categoria.getNombreConEmoji()));
        if (m.getMonto() != null) {
            partes.add(pesos(m.getMonto()));
        }
        String descripcion = m.getDescripcion();
        if (descripcion != null && !descripcion.isBlank()
                && (categoria == null || !descripcion.equalsIgnoreCase(categoria.getNombre()))) {
            partes.add(html(descripcion));
        }
        partes.add(html(medio(m.getMedioPago(), m.getCuenta())));
        partes.add(fecha(m.getFechaHora()));
        return String.join(SEPARADOR, partes);
    }

    /** "Efectivo", "Débito (Galicia)", "Sin medio de pago". */
    public static String medio(MedioPago medio, Cuenta cuenta) {
        if (medio == null && cuenta == null) {
            return "Sin medio de pago";
        }
        if (medio == null) {
            return cuenta.getNombre();
        }
        return cuenta == null ? medio.getNombre() : medio.getNombre() + " (" + cuenta.getNombre() + ")";
    }

    /** Escapa un texto para mandarlo con parse mode HTML. */
    public static String html(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static DecimalFormat formato(String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(ES_AR);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat(patron, simbolos);
    }
}
