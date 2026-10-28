package ar.gastito.recibo;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ar.gastito.ticket.Importes;

/**
 * Revisa lo que la IA leyó de un recibo de sueldo. La IA no calcula: las cuentas se controlan acá.
 * <ul>
 * <li>Remunerativos + no remunerativos − descuentos tiene que dar el neto impreso (tolerancia de $1). Si no
 * cierra, se guarda igual y el aviso dice qué no cierra.</li>
 * <li>Sin neto no se guarda nada: hay que mandar un archivo o una foto más clara.</li>
 * <li>Sin un mes de período que se pueda entender tampoco: el ingreso va en el mes del período.</li>
 * </ul>
 */
@Component
public class ValidadorRecibo {

    static final BigDecimal TOLERANCIA = BigDecimal.ONE;
    static final int LARGO_MAXIMO_EMPLEADOR = 100;
    static final int LARGO_MAXIMO_CONCEPTO = 150;
    static final int MAXIMO_CONCEPTOS = 60;
    static final String SIN_EMPLEADOR = "Sin empleador";

    private static final Pattern ANIO_MES = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})$");
    private static final Pattern MES_ANIO = Pattern.compile("^(\\d{1,2})[-/.](\\d{2}|\\d{4})$");
    private static final Pattern NOMBRE_ANIO = Pattern.compile("^([a-z]{3,})\\.?[\\s\\-/de]*(\\d{2}|\\d{4})$");
    private static final Pattern FECHA_DIA_PRIMERO =
            Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?!\\d)");
    private static final Pattern FECHA_ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final List<String> MESES = List.of("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep",
            "oct", "nov", "dic");

    private final Clock clock;

    public ValidadorRecibo(Clock clock) {
        this.clock = clock;
    }

    public sealed interface Resultado {
    }

    public record Valido(ReciboLeido recibo) implements Resultado {
    }

    /** El documento es un ticket u otra cosa. */
    public record NoEsRecibo(TipoDocumento documento) implements Resultado {
    }

    public record Ilegible(Motivo motivo) implements Resultado {
    }

    public enum Motivo {
        BORROSO,
        SIN_NETO,
        SIN_PERIODO
    }

    public Resultado validar(LecturaReciboIa ia) {
        if (ia == null) {
            return new Ilegible(Motivo.BORROSO);
        }
        TipoDocumento documento = documento(ia.tipoDocumento());
        if (documento != TipoDocumento.RECIBO_SUELDO) {
            return new NoEsRecibo(documento);
        }
        String problema = ia.problema() == null ? "" : ia.problema().strip().toLowerCase(Locale.ROOT);
        if (problema.contains("borros")) {
            return new Ilegible(Motivo.BORROSO);
        }
        Optional<BigDecimal> neto = Importes.importe(ia.neto()).map(BigDecimal::abs).filter(n -> n.signum() > 0);
        if (neto.isEmpty()) {
            return new Ilegible(Motivo.SIN_NETO);
        }
        LocalDate fechaPago = fecha(ia.fechaPago());
        Optional<YearMonth> periodo = periodo(ia.periodo());
        if (periodo.isEmpty() && fechaPago != null) {
            periodo = Optional.of(YearMonth.from(fechaPago));
        }
        if (periodo.isEmpty()) {
            return new Ilegible(Motivo.SIN_PERIODO);
        }

        List<String> avisos = new ArrayList<>();
        List<ReciboLeido.Concepto> conceptos = conceptos(ia, avisos);
        BigDecimal remunerativo = suma(conceptos, GrupoConcepto.REMUNERATIVO);
        BigDecimal noRemunerativo = suma(conceptos, GrupoConcepto.NO_REMUNERATIVO);
        BigDecimal descuentosSumados = suma(conceptos, GrupoConcepto.DESCUENTO);

        BigDecimal bruto = Importes.importe(ia.bruto()).map(BigDecimal::abs).filter(b -> b.signum() > 0)
                .orElse(remunerativo.add(noRemunerativo));
        Optional<BigDecimal> descuentosImpresos = Importes.importe(ia.totalDescuentos()).map(BigDecimal::abs);
        BigDecimal descuentos = descuentosImpresos.orElse(descuentosSumados);

        if (conceptos.isEmpty()) {
            avisos.add("No pude leer ningún concepto del recibo.");
        } else {
            BigDecimal calculado = remunerativo.add(noRemunerativo).subtract(descuentosSumados);
            if (calculado.subtract(neto.get()).abs().compareTo(TOLERANCIA) > 0) {
                avisos.add("No cierra: los conceptos dan " + pesos(calculado) + " y el neto impreso es "
                        + pesos(neto.get()) + " (diferencia " + pesos(calculado.subtract(neto.get()).abs())
                        + "). Puede faltar un renglón o estar mal leído un importe.");
            }
            if (descuentosImpresos.isPresent()
                    && descuentosSumados.subtract(descuentosImpresos.get()).abs().compareTo(TOLERANCIA) > 0) {
                avisos.add("Los descuentos suman " + pesos(descuentosSumados) + " y el recibo dice "
                        + pesos(descuentosImpresos.get()) + ".");
            }
        }

        ReciboLeido leido = new ReciboLeido(empleador(ia.empleador()), periodo.get(), fechaPago, tipo(ia.tipo()),
                conceptos, bruto, descuentos, neto.get(), avisos.isEmpty() ? null : String.join(" ", avisos));
        return new Valido(leido);
    }

    // --- Campos ---

    static TipoDocumento documento(String texto) {
        String t = normalizar(texto);
        if (t.contains("recibo")) {
            return TipoDocumento.RECIBO_SUELDO;
        }
        return t.contains("ticket") ? TipoDocumento.TICKET : TipoDocumento.OTRO;
    }

    static TipoRecibo tipo(String texto) {
        String t = normalizar(texto);
        if (t.contains("aguinaldo") || t.contains("sac") || t.contains("anual")) {
            return TipoRecibo.AGUINALDO;
        }
        if (t.contains("liquid") || t.contains("final") || t.contains("egreso")) {
            return TipoRecibo.LIQUIDACION_FINAL;
        }
        return t.contains("vacacion") ? TipoRecibo.VACACIONES : TipoRecibo.SUELDO_MENSUAL;
    }

    static GrupoConcepto grupo(String texto) {
        String t = normalizar(texto);
        if (t.contains("desc") || t.contains("deduc") || t.contains("retenc")) {
            return GrupoConcepto.DESCUENTO;
        }
        if (t.contains("no_rem") || t.contains("no rem") || t.contains("norem") || t.contains("sin retenc")) {
            return GrupoConcepto.NO_REMUNERATIVO;
        }
        return t.contains("rem") || t.contains("haber") ? GrupoConcepto.REMUNERATIVO : null;
    }

    private String empleador(String texto) {
        String limpio = recortar(DatosPersonales.limpiar(texto), LARGO_MAXIMO_EMPLEADOR);
        return limpio == null ? SIN_EMPLEADOR : limpio;
    }

    private List<ReciboLeido.Concepto> conceptos(LecturaReciboIa ia, List<String> avisos) {
        List<ReciboLeido.Concepto> conceptos = new ArrayList<>();
        if (ia.conceptos() == null) {
            return conceptos;
        }
        int sinGrupo = 0;
        for (LecturaReciboIa.Concepto c : ia.conceptos()) {
            if (c == null || conceptos.size() >= MAXIMO_CONCEPTOS) {
                continue;
            }
            String descripcion = recortar(DatosPersonales.limpiar(c.descripcion()), LARGO_MAXIMO_CONCEPTO);
            Optional<BigDecimal> importe = Importes.importe(c.importe()).map(BigDecimal::abs)
                    .filter(i -> i.signum() > 0);
            if (descripcion == null || importe.isEmpty()) {
                continue;
            }
            GrupoConcepto grupo = grupo(c.grupo());
            if (grupo == null) {
                sinGrupo++;
                continue;
            }
            conceptos.add(new ReciboLeido.Concepto(descripcion, grupo, importe.get()));
        }
        if (sinGrupo > 0) {
            avisos.add(sinGrupo + (sinGrupo == 1 ? " concepto no se pudo ubicar" : " conceptos no se pudieron ubicar")
                    + " en remunerativo, no remunerativo o descuento.");
        }
        return conceptos;
    }

    private static BigDecimal suma(List<ReciboLeido.Concepto> conceptos, GrupoConcepto grupo) {
        return conceptos.stream().filter(c -> c.grupo() == grupo).map(ReciboLeido.Concepto::importe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- Fechas ---

    /**
     * "2026-10", "10/2026", "10/26", "octubre 2026", "oct-26". Un mes futuro (más allá del siguiente) o de hace
     * más de diez años no se acepta: lo más probable es que la IA se haya equivocado.
     */
    Optional<YearMonth> periodo(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String t = normalizar(texto);
        Matcher m;
        YearMonth mes = null;
        try {
            if ((m = ANIO_MES.matcher(t)).matches()) {
                mes = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            } else if ((m = MES_ANIO.matcher(t)).matches()) {
                mes = YearMonth.of(anio(m.group(2)), Integer.parseInt(m.group(1)));
            } else if ((m = NOMBRE_ANIO.matcher(t)).matches()) {
                int indice = MESES.indexOf(m.group(1).substring(0, 3));
                if (indice >= 0) {
                    mes = YearMonth.of(anio(m.group(2)), indice + 1);
                }
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        YearMonth hoy = YearMonth.now(clock);
        return Optional.ofNullable(mes).filter(p -> !p.isAfter(hoy.plusMonths(1)) && !p.isBefore(hoy.minusYears(10)));
    }

    /** "05/11/2026" o "2026-11-05". Null si no se entiende o es una fecha imposible. */
    LocalDate fecha(String texto) {
        if (texto == null) {
            return null;
        }
        String t = texto.strip();
        try {
            Matcher m = FECHA_ISO.matcher(t);
            if (m.find()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            }
            m = FECHA_DIA_PRIMERO.matcher(t);
            if (m.find()) {
                return LocalDate.of(anio(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
        } catch (DateTimeException e) {
            return null;
        }
        return null;
    }

    private static int anio(String texto) {
        int anio = Integer.parseInt(texto);
        return texto.length() == 2 ? 2000 + anio : anio;
    }

    // --- Textos ---

    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .strip();
    }

    private static String recortar(String texto, int largo) {
        if (texto == null) {
            return null;
        }
        return texto.length() > largo ? texto.substring(0, largo).strip() : texto;
    }

    private static String pesos(BigDecimal monto) {
        return "$" + String.format(Locale.forLanguageTag("es-AR"), "%,.2f", monto);
    }
}
