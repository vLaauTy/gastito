package ar.gastito.sueldo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La calculadora de sueldo: calcula el mes con la escala vigente, lo guarda y lo compara con lo cobrado. También
 * administra los conceptos y las escalas (que cambian con las paritarias).
 */
@Service
public class SueldoService {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final ConceptoSueldoRepository conceptos;
    private final EscalaSueldoRepository escalas;
    private final CalculoSueldoRepository calculos;
    private final SueldoProperties properties;
    private final Clock clock;

    public SueldoService(ConceptoSueldoRepository conceptos, EscalaSueldoRepository escalas,
            CalculoSueldoRepository calculos, SueldoProperties properties, Clock clock) {
        this.conceptos = conceptos;
        this.escalas = escalas;
        this.calculos = calculos;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param guardado si es el cálculo guardado del mes (y no una cuenta en vivo)
     * @param escalaDesde desde cuándo rige la escala usada
     */
    public record VistaCalculo(YearMonth periodo, boolean guardado, Long escalaId, LocalDate escalaDesde,
            CalculadoraSueldo.Resultado resultado) {
    }

    /**
     * @param porcentaje lo cobrado contra lo calculado, con un decimal (negativo si se cobró menos); null si lo
     *                   calculado es cero
     * @param fuera      si la diferencia pasa la tolerancia
     */
    public record Comparacion(YearMonth periodo, BigDecimal calculado, BigDecimal cobrado, BigDecimal diferencia,
            BigDecimal porcentaje, boolean fuera) {
    }

    // --- Cálculo ---

    /** Las horas del mes pasan por concepto (los que no se nombran cuentan cero); los ajustes pisan un importe. */
    @Transactional(readOnly = true)
    public VistaCalculo calcular(YearMonth periodo, Map<Long, BigDecimal> horas, Map<Long, BigDecimal> ajustes) {
        EscalaSueldo escala = escalaVigente(periodo);
        validar(horas, ajustes);
        return new VistaCalculo(periodo, false, escala.getId(), escala.getVigenteDesde(),
                CalculadoraSueldo.calcular(conceptos.findAllByOrderByOrdenAscIdAsc(), escala, horas, ajustes));
    }

    /** Guarda el cálculo del mes; si ya había uno, lo reemplaza. */
    @Transactional
    public VistaCalculo guardar(YearMonth periodo, Map<Long, BigDecimal> horas, Map<Long, BigDecimal> ajustes) {
        VistaCalculo vista = calcular(periodo, horas, ajustes);
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        Optional<CalculoSueldo> existente = calculos.findByPeriodo(periodo.atDay(1));
        if (existente.isPresent()) {
            existente.get().reemplazar(vista.escalaId(), vista.resultado(), ahora);
        } else {
            calculos.save(new CalculoSueldo(periodo, vista.escalaId(), vista.resultado(), ahora));
        }
        return new VistaCalculo(periodo, true, vista.escalaId(), vista.escalaDesde(), vista.resultado());
    }

    @Transactional
    public void borrarCalculo(YearMonth periodo) {
        calculos.findByPeriodo(periodo.atDay(1)).ifPresent(calculos::delete);
    }

    /** El cálculo guardado de un mes. */
    @Transactional(readOnly = true)
    public Optional<CalculoSueldo> calculoDelMes(YearMonth periodo) {
        return calculos.findByPeriodo(periodo.atDay(1));
    }

    /** Los cálculos guardados entre dos meses (incluidos), del más viejo al más nuevo. */
    @Transactional(readOnly = true)
    public List<CalculoSueldo> calculosEntre(YearMonth desde, YearMonth hasta) {
        return calculos.findByPeriodoBetweenOrderByPeriodo(desde.atDay(1), hasta.atDay(1));
    }

    /** Los conceptos por horas activos, en orden: el bot asigna "10 6 30" a estos, en ese orden. */
    @Transactional(readOnly = true)
    public List<ConceptoSueldo> conceptosDeHoras() {
        return conceptos.findAllByOrderByOrdenAscIdAsc().stream()
                .filter(c -> c.isActivo() && c.getTipo() == TipoConceptoSueldo.HORAS).toList();
    }

    /** Las horas del bot por orden de concepto: "10 6 30" son las horas al 50%, al 100% y nocturnas. */
    public Map<Long, BigDecimal> horasPorOrden(List<BigDecimal> cantidades) {
        List<ConceptoSueldo> deHoras = conceptosDeHoras();
        if (cantidades.size() > deHoras.size()) {
            throw new IllegalArgumentException("Hay " + deHoras.size() + " tipos de horas y pasaste "
                    + cantidades.size());
        }
        Map<Long, BigDecimal> horas = new HashMap<>();
        for (int i = 0; i < cantidades.size(); i++) {
            horas.put(deHoras.get(i).getId(), cantidades.get(i));
        }
        return horas;
    }

    // --- Cobrado contra calculado ---

    /** Lo cobrado contra el cálculo del mes; vacío si ese mes no tiene cálculo. */
    @Transactional(readOnly = true)
    public Optional<Comparacion> comparar(YearMonth periodo, BigDecimal cobrado) {
        return calculoDelMes(periodo).map(c -> comparar(periodo, c.getNetoTotal(), cobrado));
    }

    public Comparacion comparar(YearMonth periodo, BigDecimal calculado, BigDecimal cobrado) {
        BigDecimal diferencia = cobrado.subtract(calculado);
        BigDecimal porcentaje = calculado.signum() == 0 ? null
                : diferencia.multiply(CIEN).divide(calculado, 1, RoundingMode.HALF_UP);
        boolean fuera = porcentaje == null ? diferencia.signum() != 0
                : diferencia.multiply(CIEN).divide(calculado, 6, RoundingMode.HALF_UP).abs()
                        .compareTo(properties.toleranciaCobroPorcentaje()) > 0;
        return new Comparacion(periodo, calculado, cobrado, diferencia, porcentaje, fuera);
    }

    // --- Conceptos ---

    @Transactional(readOnly = true)
    public List<ConceptoSueldo> listarConceptos() {
        return conceptos.findAllByOrderByOrdenAscIdAsc();
    }

    /** Crea el concepto (id null) o lo edita. Un concepto que ya se usó no se borra: se desactiva. */
    @Transactional
    public ConceptoSueldo guardarConcepto(Long id, String nombre, TipoConceptoSueldo tipo, Integer factorNum,
            Integer factorDen, Integer orden, boolean activo) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Poné el nombre del concepto");
        }
        if (tipo == null) {
            throw new IllegalArgumentException("Elegí el tipo de concepto");
        }
        String limpio = nombre.strip();
        if (limpio.length() > 100) {
            throw new IllegalArgumentException("El nombre es muy largo");
        }
        int posicion = orden != null ? orden : listarConceptos().size() + 1;
        if (id == null) {
            return conceptos.save(new ConceptoSueldo(limpio, tipo, factorNum, factorDen, posicion));
        }
        ConceptoSueldo c = conceptos.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el concepto " + id));
        c.editar(limpio, tipo, factorNum, factorDen, posicion, activo);
        return c;
    }

    // --- Escalas ---

    @Transactional(readOnly = true)
    public List<EscalaSueldo> listarEscalas() {
        return escalas.findAllByOrderByVigenteDesdeDesc();
    }

    /** La escala que rige en un mes. */
    @Transactional(readOnly = true)
    public EscalaSueldo escalaVigente(YearMonth periodo) {
        return escalas.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(periodo.atDay(1))
                .orElseThrow(() -> new IllegalArgumentException("No hay una escala de sueldo para "
                        + nombreMes(periodo) + ". Cargala en el dashboard (Ingresos → Calculadora)."));
    }

    /** Una escala nueva desde el primer día del mes de {@code desde}. */
    @Transactional
    public EscalaSueldo crearEscala(LocalDate desde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
        LocalDate primero = validarEscala(desde, valorHora, valores);
        if (escalas.findByVigenteDesde(primero).isPresent()) {
            throw new IllegalArgumentException("Ya hay una escala desde esa fecha: editala en lugar de crear otra");
        }
        return escalas.save(new EscalaSueldo(primero, valorHora, valores));
    }

    @Transactional
    public EscalaSueldo editarEscala(Long id, LocalDate desde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
        LocalDate primero = validarEscala(desde, valorHora, valores);
        EscalaSueldo escala = escalas.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe la escala " + id));
        escalas.findByVigenteDesde(primero).filter(otra -> !otra.getId().equals(id)).ifPresent(otra -> {
            throw new IllegalArgumentException("Ya hay otra escala desde esa fecha");
        });
        escala.editar(primero, valorHora, valores);
        return escala;
    }

    private LocalDate validarEscala(LocalDate desde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
        if (desde == null) {
            throw new IllegalArgumentException("Elegí desde cuándo rige la escala");
        }
        if (valorHora == null || valorHora.signum() < 0) {
            throw new IllegalArgumentException("El valor de la hora normal no puede ser negativo");
        }
        for (BigDecimal valor : valores.values()) {
            if (valor == null || valor.signum() < 0) {
                throw new IllegalArgumentException("Los montos y porcentajes no pueden ser negativos");
            }
        }
        return desde.withDayOfMonth(1);
    }

    private static void validar(Map<Long, BigDecimal> horas, Map<Long, BigDecimal> ajustes) {
        for (BigDecimal h : horas == null ? List.<BigDecimal>of() : horas.values()) {
            if (h == null || h.signum() < 0) {
                throw new IllegalArgumentException("Las horas no pueden ser negativas");
            }
        }
        for (BigDecimal a : ajustes == null ? List.<BigDecimal>of() : ajustes.values()) {
            if (a == null || a.signum() < 0) {
                throw new IllegalArgumentException("Un ajuste no puede ser negativo");
            }
        }
    }

    static String nombreMes(YearMonth mes) {
        return mes.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("es-AR"))
                + " " + mes.getYear();
    }
}
