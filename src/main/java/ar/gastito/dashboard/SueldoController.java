package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.gastito.recibo.SeguimientoSueldo;
import ar.gastito.sueldo.CalculadoraSueldo;
import ar.gastito.sueldo.CalculoSueldo;
import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;

/**
 * La calculadora de sueldo para la sección Ingresos: el cálculo en vivo y guardado de cada mes, los conceptos, las
 * escalas con vigencia y el seguimiento mes por mes (calculado, cobrado y recibo).
 */
@RestController
@RequestMapping("/api/sueldo")
public class SueldoController {

    static final int MESES_DEL_SEGUIMIENTO = 12;
    static final int MAXIMO_MESES = 36;

    private final SueldoService sueldo;
    private final SeguimientoSueldo seguimiento;
    private final Clock clock;

    public SueldoController(SueldoService sueldo, SeguimientoSueldo seguimiento, Clock clock) {
        this.sueldo = sueldo;
        this.seguimiento = seguimiento;
        this.clock = clock;
    }

    // --- Cálculo ---

    public record Linea(Long conceptoId, String nombre, String tipo, BigDecimal cantidad, BigDecimal valor,
            BigDecimal importe, boolean ajustado) {
    }

    /**
     * @param existe   false si ese mes no tiene cálculo guardado (el resto viene vacío)
     * @param guardado si es el cálculo guardado y no una cuenta en vivo
     */
    public record Calculo(boolean existe, boolean guardado, YearMonth mes, Long escalaId, LocalDate escalaDesde,
            BigDecimal valorHora, BigDecimal bruto, BigDecimal descuentos, BigDecimal netoHaberes,
            BigDecimal noRemunerativo, BigDecimal netoTotal, List<Linea> lineas) {
    }

    public record PedidoCalculo(String mes, Map<Long, BigDecimal> horas, Map<Long, BigDecimal> ajustes) {
    }

    /** El cálculo guardado de un mes (por defecto, el actual). */
    @GetMapping("/calculo")
    public Calculo calculo(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        YearMonth m = mes != null ? mes : YearMonth.now(clock);
        Optional<CalculoSueldo> guardado = sueldo.calculoDelMes(m);
        if (guardado.isEmpty()) {
            return new Calculo(false, false, m, null, null, null, null, null, null, null, null, List.of());
        }
        CalculoSueldo c = guardado.get();
        LocalDate desde = sueldo.listarEscalas().stream().filter(e -> c.getEscalaId().equals(e.getId()))
                .map(EscalaSueldo::getVigenteDesde).findFirst().orElse(null);
        return new Calculo(true, true, m, c.getEscalaId(), desde, c.getValorHora(), c.getBruto(), c.getDescuentos(),
                c.getNetoHaberes(), c.getNoRemunerativo(), c.getNetoTotal(), c.getLineas().stream()
                        .map(l -> new Linea(l.getConceptoId(), l.getNombre(), l.getTipo().name(), l.getCantidad(),
                                l.getValor(), l.getImporte(), l.isAjustado())).toList());
    }

    /** La cuenta en vivo con las horas del formulario, sin guardar. */
    @PostMapping("/calcular")
    public Calculo calcular(@RequestBody PedidoCalculo pedido) {
        return vista(sueldo.calcular(mes(pedido.mes()), pedido.horas(), pedido.ajustes()));
    }

    /** Guarda el cálculo del mes (si ya había uno, lo reemplaza). */
    @PostMapping("/calculo")
    public Calculo guardar(@RequestBody PedidoCalculo pedido) {
        return vista(sueldo.guardar(mes(pedido.mes()), pedido.horas(), pedido.ajustes()));
    }

    private static Calculo vista(SueldoService.VistaCalculo v) {
        CalculadoraSueldo.Resultado r = v.resultado();
        return new Calculo(true, v.guardado(), v.periodo(), v.escalaId(), v.escalaDesde(), r.valorHora(), r.bruto(),
                r.descuentos(), r.netoHaberes(), r.noRemunerativo(), r.netoTotal(), r.lineas().stream()
                        .map(l -> new Linea(l.conceptoId(), l.nombre(), l.tipo().name(), l.cantidad(), l.valor(),
                                l.importe(), l.ajustado())).toList());
    }

    private static YearMonth mes(String texto) {
        try {
            return YearMonth.parse(texto);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("El mes tiene que ser AAAA-MM");
        }
    }

    // --- Conceptos ---

    public record Concepto(Long id, String nombre, String tipo, String tipoNombre, Integer factorNum,
            Integer factorDen, BigDecimal factor, int orden, boolean activo) {
    }

    public record PedidoConcepto(String nombre, String tipo, Integer factorNum, Integer factorDen, Integer orden,
            Boolean activo) {
    }

    @GetMapping("/conceptos")
    public List<Concepto> conceptos() {
        return sueldo.listarConceptos().stream().map(SueldoController::concepto).toList();
    }

    @PostMapping("/conceptos")
    public Concepto crearConcepto(@RequestBody PedidoConcepto p) {
        return concepto(sueldo.guardarConcepto(null, p.nombre(), tipo(p.tipo()), p.factorNum(), p.factorDen(),
                p.orden(), true));
    }

    @PutMapping("/conceptos/{id}")
    public Concepto editarConcepto(@PathVariable long id, @RequestBody PedidoConcepto p) {
        return concepto(sueldo.guardarConcepto(id, p.nombre(), tipo(p.tipo()), p.factorNum(), p.factorDen(),
                p.orden(), p.activo() == null || p.activo()));
    }

    private static TipoConceptoSueldo tipo(String texto) {
        try {
            return TipoConceptoSueldo.valueOf(texto);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Elegí el tipo de concepto");
        }
    }

    private static Concepto concepto(ConceptoSueldo c) {
        return new Concepto(c.getId(), c.getNombre(), c.getTipo().name(), c.getTipo().getNombre(), c.getFactorNum(),
                c.getFactorDen(), c.factorDecimal(), c.getOrden(), c.isActivo());
    }

    // --- Escalas ---

    /** @param valores el monto fijo (o el porcentaje, en los descuentos) por id de concepto */
    public record Escala(Long id, LocalDate vigenteDesde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
    }

    public record PedidoEscala(LocalDate vigenteDesde, BigDecimal valorHora, Map<Long, BigDecimal> valores) {
    }

    /** Todas las escalas, de la más nueva a la más vieja. */
    @GetMapping("/escalas")
    public List<Escala> escalas() {
        return sueldo.listarEscalas().stream().map(SueldoController::escala).toList();
    }

    @PostMapping("/escalas")
    public Escala crearEscala(@RequestBody PedidoEscala p) {
        return escala(sueldo.crearEscala(p.vigenteDesde(), p.valorHora(), valores(p)));
    }

    @PutMapping("/escalas/{id}")
    public Escala editarEscala(@PathVariable long id, @RequestBody PedidoEscala p) {
        return escala(sueldo.editarEscala(id, p.vigenteDesde(), p.valorHora(), valores(p)));
    }

    private static Map<Long, BigDecimal> valores(PedidoEscala p) {
        return p.valores() == null ? Map.of() : p.valores();
    }

    private static Escala escala(EscalaSueldo e) {
        return new Escala(e.getId(), e.getVigenteDesde(), e.getValorHora(), Map.copyOf(e.getValores()));
    }

    // --- Seguimiento ---

    /** Mes por mes: calculado, cobrado y recibo, con las diferencias. */
    @GetMapping("/meses")
    public List<SeguimientoSueldo.Mes> meses(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth hasta,
            @RequestParam(defaultValue = "" + MESES_DEL_SEGUIMIENTO) int meses) {
        if (meses < 1 || meses > MAXIMO_MESES) {
            throw new IllegalArgumentException("meses va de 1 a " + MAXIMO_MESES);
        }
        return seguimiento.meses(hasta != null ? hasta : YearMonth.now(clock), meses);
    }

    /** Un solo mes. */
    @GetMapping("/meses/{mes}")
    public SeguimientoSueldo.Mes unMes(@PathVariable String mes) {
        if (mes == null) {
            throw new NoSuchElementException("Falta el mes");
        }
        return seguimiento.mes(mes(mes));
    }
}
