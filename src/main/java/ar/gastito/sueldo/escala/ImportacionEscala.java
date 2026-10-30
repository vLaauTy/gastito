package ar.gastito.sueldo.escala;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ar.gastito.sueldo.ConceptoSueldo;
import ar.gastito.sueldo.ConceptoSueldoRepository;
import ar.gastito.sueldo.EscalaSueldo;
import ar.gastito.sueldo.EscalaSueldoRepository;
import ar.gastito.sueldo.SueldoProperties;
import ar.gastito.sueldo.SueldoService;
import ar.gastito.sueldo.TipoConceptoSueldo;

/**
 * Pasa la hoja del sindicato (ya leída de un Excel o de una foto) a escalas, una por mes. Cada fila se reconoce por
 * sus alias; lo que la hoja no trae (el adicional CCT, los porcentajes de descuento) se mantiene de la escala anterior.
 * Antes de guardar se arma una propuesta para mostrar: los valores con su cambio contra la escala anterior, lo que se
 * mantiene y el control con "Valores netos".
 */
@Service
public class ImportacionEscala {

    static final BigDecimal TOLERANCIA_CONTROL = new BigDecimal("5");
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final ConceptoSueldoRepository conceptos;
    private final EscalaSueldoRepository escalas;
    private final AliasEscalaRepository aliases;
    private final SueldoService sueldo;
    private final SueldoProperties properties;

    public ImportacionEscala(ConceptoSueldoRepository conceptos, EscalaSueldoRepository escalas,
            AliasEscalaRepository aliases, SueldoService sueldo, SueldoProperties properties) {
        this.conceptos = conceptos;
        this.escalas = escalas;
        this.aliases = aliases;
        this.sueldo = sueldo;
        this.properties = properties;
    }

    /** Qué es cada valor, para mostrarlo: la hora normal, un monto, el valor de una hora o un porcentaje. */
    public enum Clase {
        HORA_NORMAL, MONTO, HORA, PORCENTAJE
    }

    /**
     * @param conceptoId null en la hora normal
     * @param antes      el de la escala anterior; null si no había
     * @param variacion  el cambio en %, con un decimal; null si antes no había
     */
    public record Valor(Long conceptoId, String nombre, Clase clase, BigDecimal antes, BigDecimal ahora,
            BigDecimal variacion) {

        public boolean cambio() {
            return antes == null || antes.compareTo(ahora) != 0;
        }
    }

    /**
     * Valores netos = remunerativos de la hoja × (1 − el descuento fijo de la hoja, 20 %) + no remunerativos de la hoja.
     *
     * @param hoja       lo que dice la fila "Valores netos"
     * @param calculado  lo mismo calculado en Java con lo que se leyó
     * @param porcentaje el descuento fijo de la hoja que se usó
     */
    public record Control(BigDecimal hoja, BigDecimal calculado, BigDecimal diferencia, boolean ok,
            BigDecimal porcentaje) {
    }

    /**
     * @param leidos            lo que trae la hoja, en el orden de los conceptos (la hora normal primero)
     * @param mantenidos        lo que no trae la hoja y queda de la escala anterior (o mis descuentos por defecto, sin
     *                          "antes")
     * @param control           null si la hoja no tiene "Valores netos"
     * @param reemplazaEscalaId la escala que ya hay para ese mes, que se reemplaza al guardar; null si no hay
     */
    public record MesPropuesto(YearMonth mes, BigDecimal valorHora, Map<Long, BigDecimal> valores,
            List<Valor> leidos, List<Valor> mantenidos, Control control, Long reemplazaEscalaId) {

        public boolean reemplaza() {
            return reemplazaEscalaId != null;
        }
    }

    /** @param ignoradas las filas de la hoja con un importe que no son de ningún concepto (sin alias), sin repetir */
    public record Propuesta(List<MesPropuesto> meses, List<String> ignoradas) {

        public List<YearMonth> listaDeMeses() {
            return meses.stream().map(MesPropuesto::mes).toList();
        }
    }

    /** @param reemplazada si ese mes ya tenía una escala */
    public record Guardada(YearMonth mes, Long escalaId, boolean reemplazada) {
    }

    private record Destino(DestinoAlias destino, Long conceptoId) {
    }

    /** @param porDefecto los descuentos que vienen de mis valores por defecto (en la columna anterior de la hoja) */
    private record Base(BigDecimal valorHora, Map<Long, BigDecimal> valores, Set<Long> porDefecto) {
    }

    // --- Propuesta ---

    /**
     * @throws IllegalArgumentException si a la hoja le falta el mes, si un valor de una fila conocida no tiene formato
     *                                  de plata o si una columna no tiene ninguna fila conocida
     */
    @Transactional(readOnly = true)
    public Propuesta proponer(HojaEscala hoja) {
        if (hoja.faltaElMes()) {
            throw new IllegalArgumentException("La planilla no dice de qué mes es");
        }
        List<ConceptoSueldo> todos = conceptos.findAllByOrderByOrdenAscIdAsc();
        Map<Long, ConceptoSueldo> porId = new HashMap<>();
        todos.forEach(c -> porId.put(c.getId(), c));
        Map<String, Destino> reconocer = reconocedor(todos);

        List<HojaEscala.Columna> columnas = new ArrayList<>(hoja.columnas());
        columnas.sort(Comparator.comparing(HojaEscala.Columna::mes));
        Set<YearMonth> vistos = new HashSet<>();
        List<String> errores = new ArrayList<>();
        Set<String> ignoradas = new LinkedHashSet<>();
        List<MesPropuesto> meses = new ArrayList<>();
        for (HojaEscala.Columna columna : columnas) {
            String mes = MesHoja.corto(columna.mes());
            if (!vistos.add(columna.mes())) {
                errores.add("La hoja tiene dos columnas de " + mes);
                continue;
            }
            BigDecimal horaNormal = null;
            BigDecimal netos = null;
            Map<Long, BigDecimal> leidos = new LinkedHashMap<>();
            for (HojaEscala.Fila fila : columna.filas()) {
                Destino d = reconocer.get(NombreFila.clave(fila.nombre()));
                if (d == null) {
                    if (fila.valor() != null) {
                        ignoradas.add(fila.nombre());
                    }
                    continue;
                }
                if (fila.valor() == null) {
                    errores.add(mes + " · " + fila.nombre() + ": no entendí «" + fila.texto() + "»");
                    continue;
                }
                switch (d.destino()) {
                    case HORA_NORMAL -> horaNormal = horaNormal == null ? fila.valor() : horaNormal;
                    case VALORES_NETOS -> netos = netos == null ? fila.valor() : netos;
                    case CONCEPTO -> leidos.putIfAbsent(d.conceptoId(), fila.valor());
                }
            }
            if (leidos.isEmpty() && horaNormal == null) {
                errores.add(mes + ": no encontré ninguna fila conocida (básico, presentismo, horas…)");
                continue;
            }
            Optional<EscalaSueldo> existente = escalas.findByVigenteDesde(columna.mes().atDay(1));
            Base base = existente.map(e -> new Base(e.getValorHora(), e.getValores(), Set.of()))
                    .or(() -> Optional.ofNullable(meses.isEmpty() ? null : baseDe(meses.getLast())))
                    .or(() -> escalas.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(
                            columna.mes().atDay(1)).map(e -> new Base(e.getValorHora(), e.getValores(), Set.of())))
                    .orElse(null);
            BigDecimal valorHora = horaNormal != null ? horaNormal : base == null ? null : base.valorHora();
            if (valorHora == null) {
                errores.add(mes + ": falta el valor de la hora normal (no hay una escala anterior de donde sacarlo)");
                continue;
            }
            meses.add(mes(columna.mes(), todos, porId, base, horaNormal, valorHora, leidos, netos,
                    existente.map(EscalaSueldo::getId).orElse(null)));
        }
        if (!errores.isEmpty()) {
            throw new IllegalArgumentException("No pude usar la hoja:\n" + String.join("\n", errores));
        }
        return new Propuesta(List.copyOf(meses), List.copyOf(ignoradas));
    }

    private static Base baseDe(MesPropuesto m) {
        return new Base(m.valorHora(), m.valores(), m.mantenidos().stream()
                .filter(v -> v.clase() == Clase.PORCENTAJE && v.antes() == null).map(Valor::conceptoId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private MesPropuesto mes(YearMonth mes, List<ConceptoSueldo> todos, Map<Long, ConceptoSueldo> porId, Base base,
            BigDecimal horaNormal, BigDecimal valorHora, Map<Long, BigDecimal> leidos, BigDecimal netos,
            Long reemplaza) {
        Map<Long, BigDecimal> antes = base == null ? Map.of() : base.valores();
        Map<Long, BigDecimal> valores = new HashMap<>(antes);
        // El valor propio de una hora que la hoja ya no trae no se arrastra: vuelve a ser la normal × el factor.
        valores.keySet().removeIf(id -> porId.containsKey(id) && porId.get(id).getTipo() == TipoConceptoSueldo.HORAS
                && !leidos.containsKey(id));
        valores.putAll(leidos);
        // Sin escala anterior de donde sacarlos, los descuentos son los míos por defecto (11 + 3 + 3 + 2,5 %).
        List<ConceptoSueldo> descuentos = todos.stream()
                .filter(c -> c.isActivo() && c.getTipo() == TipoConceptoSueldo.DESCUENTO_PORCENTAJE).toList();
        List<BigDecimal> porDefecto = properties.descuentosPorDefecto();
        Set<Long> deFabrica = new HashSet<>(base == null ? Set.of() : base.porDefecto());
        for (int i = 0; i < descuentos.size() && i < porDefecto.size(); i++) {
            if (valores.putIfAbsent(descuentos.get(i).getId(), porDefecto.get(i)) == null) {
                deFabrica.add(descuentos.get(i).getId());
            }
        }

        List<Valor> deLaHoja = new ArrayList<>();
        List<Valor> mantenidos = new ArrayList<>();
        BigDecimal horaAntes = base == null ? null : base.valorHora();
        if (horaNormal != null) {
            deLaHoja.add(valor(null, "Hora normal", Clase.HORA_NORMAL, horaAntes, horaNormal));
        } else {
            mantenidos.add(valor(null, "Hora normal", Clase.HORA_NORMAL, horaAntes, valorHora));
        }
        for (ConceptoSueldo c : todos) {
            if (leidos.containsKey(c.getId())) {
                deLaHoja.add(valor(c.getId(), c.getNombre(), clase(c), antes.get(c.getId()), leidos.get(c.getId())));
            } else if (c.isActivo() && c.getTipo() != TipoConceptoSueldo.HORAS && valores.get(c.getId()) != null
                    && valores.get(c.getId()).signum() > 0) {
                // Uno por defecto no tiene "antes": no venía de ninguna escala.
                mantenidos.add(valor(c.getId(), c.getNombre(), clase(c),
                        deFabrica.contains(c.getId()) ? null : valores.get(c.getId()), valores.get(c.getId())));
            }
        }
        return new MesPropuesto(mes, valorHora, Map.copyOf(valores), List.copyOf(deLaHoja), List.copyOf(mantenidos),
                netos == null ? null : control(todos, leidos, properties.porcentajeControlHoja(), netos), reemplaza);
    }

    /**
     * Valores netos como los calcula la hoja: lo remunerativo que trae × (1 − el descuento fijo de la hoja) + lo no
     * remunerativo que trae. El descuento es el de la hoja (20 %), no el mío: el control dice si la hoja se leyó bien.
     */
    static Control control(List<ConceptoSueldo> todos, Map<Long, BigDecimal> leidos, BigDecimal porcentaje,
            BigDecimal netos) {
        BigDecimal remunerativo = BigDecimal.ZERO;
        BigDecimal noRemunerativo = BigDecimal.ZERO;
        for (ConceptoSueldo c : todos) {
            BigDecimal leido = leidos.get(c.getId());
            if (leido == null) {
                continue;
            }
            if (c.getTipo() == TipoConceptoSueldo.REMUNERATIVO_FIJO) {
                remunerativo = remunerativo.add(leido);
            } else if (c.getTipo() == TipoConceptoSueldo.NO_REMUNERATIVO) {
                noRemunerativo = noRemunerativo.add(leido);
            }
        }
        BigDecimal calculado = remunerativo
                .multiply(BigDecimal.ONE.subtract(porcentaje.divide(CIEN, 10, RoundingMode.HALF_UP)))
                .add(noRemunerativo).setScale(2, RoundingMode.HALF_UP);
        BigDecimal diferencia = netos.subtract(calculado);
        return new Control(netos, calculado, diferencia, diferencia.abs().compareTo(TOLERANCIA_CONTROL) <= 0,
                porcentaje);
    }

    private static Valor valor(Long conceptoId, String nombre, Clase clase, BigDecimal antes, BigDecimal ahora) {
        BigDecimal variacion = antes == null || antes.signum() == 0 ? null
                : ahora.subtract(antes).multiply(CIEN).divide(antes, 1, RoundingMode.HALF_UP);
        return new Valor(conceptoId, nombre, clase, antes, ahora, variacion);
    }

    private static Clase clase(ConceptoSueldo c) {
        return switch (c.getTipo()) {
            case HORAS -> Clase.HORA;
            case DESCUENTO_PORCENTAJE -> Clase.PORCENTAJE;
            case REMUNERATIVO_FIJO, NO_REMUNERATIVO -> Clase.MONTO;
        };
    }

    /** Cada nombre de fila conocido: el nombre del concepto y, por encima, sus alias. */
    private Map<String, Destino> reconocedor(List<ConceptoSueldo> todos) {
        Map<String, Destino> reconocer = new HashMap<>();
        for (ConceptoSueldo c : todos) {
            if (c.isActivo()) {
                reconocer.put(NombreFila.clave(c.getNombre()), new Destino(DestinoAlias.CONCEPTO, c.getId()));
            }
        }
        for (AliasEscala a : aliases.findAll()) {
            reconocer.put(a.getClave(), new Destino(a.getDestino(), a.getConceptoId()));
        }
        return reconocer;
    }

    // --- Guardado ---

    /** Guarda los meses elegidos de la propuesta: crea la escala de cada mes o reemplaza la que había. */
    @Transactional
    public List<Guardada> guardar(Propuesta propuesta, Collection<YearMonth> elegidos) {
        List<Guardada> guardadas = new ArrayList<>();
        for (MesPropuesto m : propuesta.meses()) {
            if (!elegidos.contains(m.mes())) {
                continue;
            }
            Optional<EscalaSueldo> existente = escalas.findByVigenteDesde(m.mes().atDay(1));
            EscalaSueldo escala = existente.isPresent()
                    ? sueldo.editarEscala(existente.get().getId(), m.mes().atDay(1), m.valorHora(), m.valores())
                    : sueldo.crearEscala(m.mes().atDay(1), m.valorHora(), m.valores());
            guardadas.add(new Guardada(m.mes(), escala.getId(), existente.isPresent()));
        }
        if (guardadas.isEmpty()) {
            throw new IllegalArgumentException("Elegí al menos un mes");
        }
        return guardadas;
    }

    // --- Alias ---

    @Transactional(readOnly = true)
    public List<AliasEscala> listarAlias() {
        return aliases.findAllByOrderByIdAsc();
    }

    @Transactional
    public AliasEscala crearAlias(String alias, DestinoAlias destino, Long conceptoId) {
        if (alias == null || NombreFila.clave(alias).isEmpty()) {
            throw new IllegalArgumentException("Escribí el nombre de la fila como está en la hoja");
        }
        String limpio = alias.strip();
        if (limpio.length() > 100) {
            throw new IllegalArgumentException("El nombre es muy largo");
        }
        if (destino == null) {
            throw new IllegalArgumentException("Elegí a qué corresponde la fila");
        }
        if (destino == DestinoAlias.CONCEPTO && (conceptoId == null || !conceptos.existsById(conceptoId))) {
            throw new IllegalArgumentException("Elegí el concepto de la fila");
        }
        aliases.findByClave(NombreFila.clave(limpio)).ifPresent(a -> {
            throw new IllegalArgumentException("Ya hay una fila «" + a.getAlias() + "» que se lee igual");
        });
        return aliases.save(new AliasEscala(limpio, destino, conceptoId));
    }

    @Transactional
    public void borrarAlias(long id) {
        AliasEscala a = aliases.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el alias " + id));
        aliases.delete(a);
    }
}
