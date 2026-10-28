package ar.gastito.consulta;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.ControlNumeros;
import ar.gastito.ia.Prompts;
import ar.gastito.ia.PropositoIa;
import ar.gastito.recibo.AnalisisRecibos;

/**
 * Responde una pregunta en lenguaje natural con tool use: la IA elige entre herramientas fijas implementadas en
 * Java ({@link HerramientasConsulta}), Java las ejecuta y la IA redacta la respuesta con los resultados. La IA
 * nunca escribe SQL, las fechas que pide se validan, y cada número de la respuesta tiene que estar en los
 * resultados (si no, se descarta su texto y se muestran solo los resultados).
 */
@Service
public class ConsultaService {

    private static final Logger log = LoggerFactory.getLogger(ConsultaService.class);

    static final String PROMPT = "responder-pregunta";
    static final LocalDate FECHA_MINIMA = LocalDate.of(2000, 1, 1);
    static final long DIAS_MAXIMOS = 366L * 5;
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter HOY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES_AR);
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", ES_AR);

    private final ClienteIa ia;
    private final HerramientasConsulta herramientas;
    private final AnalisisRecibos recibos;
    private final CatalogoService catalogos;
    private final Prompts prompts;
    private final ObjectMapper json;
    private final GastitoProperties.Consultas config;
    private final Clock clock;

    public ConsultaService(ClienteIa ia, HerramientasConsulta herramientas, AnalisisRecibos recibos,
            CatalogoService catalogos, Prompts prompts, ObjectMapper json, GastitoProperties properties, Clock clock) {
        this.ia = ia;
        this.herramientas = herramientas;
        this.recibos = recibos;
        this.catalogos = catalogos;
        this.prompts = prompts;
        this.json = json;
        this.config = properties.consultas();
        this.clock = clock;
    }

    /**
     * @param texto       la respuesta de la IA, ya controlada; null si se descartó o la IA no estuvo
     * @param resultados  lo que devolvieron las herramientas (para mostrar los números si no hay texto)
     * @param descartado  la IA escribió un número que no venía de las herramientas
     * @param usoIaId     un registro en uso_ia con todas las llamadas de la pregunta
     */
    public record Respuesta(String texto, List<Object> resultados, boolean descartado, Long usoIaId) {
    }

    /** @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA */
    public Respuesta responder(String pregunta) {
        LocalDate hoy = LocalDate.now(clock);
        Catalogo catalogo = catalogos.cargar();
        String instrucciones = prompts.cargar(PROMPT, Map.of(
                "hoy", HOY.format(hoy),
                "rangos", rangos(hoy),
                "categorias", nombres(catalogo.categorias()),
                "mediosPago", nombres(catalogo.mediosPago()),
                "cuentas", nombres(catalogo.cuentas())));

        List<Object> resultados = new ArrayList<>();
        ClienteIa.Conversacion conversacion = ia.conversar(config.modelo(), config.maxTokens(), instrucciones,
                pregunta, definiciones(), config.maxLlamadas(),
                (nombre, parametros) -> ejecutar(nombre, parametros, hoy, resultados), PropositoIa.CONSULTA);

        String texto = conversacion.texto();
        if (texto == null || texto.isBlank()) {
            return new Respuesta(null, resultados, false, conversacion.usoIaId());
        }
        Set<BigDecimal> permitidos = new HashSet<>(ControlNumeros.numerosDe(json.valueToTree(resultados)));
        // Lo que dijo el usuario también vale ("del 3 al 10 de octubre").
        permitidos.addAll(ControlNumeros.numerosDe(json.valueToTree(pregunta)));
        ControlNumeros.Resultado control = ControlNumeros.revisar(texto, permitidos);
        if (!control.valido()) {
            log.warn("La IA escribió números que no vienen de las herramientas: {}", control.inventados());
            return new Respuesta(null, resultados, true, conversacion.usoIaId());
        }
        return new Respuesta(texto.strip(), resultados, false, conversacion.usoIaId());
    }

    // --- Herramientas ---

    /** Ejecuta lo que pidió la IA. Un parámetro inválido vuelve como error, para que la IA se corrija. */
    ClienteIa.ResultadoHerramienta ejecutar(String nombre, JsonNode p, LocalDate hoy, List<Object> resultados) {
        try {
            Object resultado = switch (nombre) {
                case "totalGastado" -> herramientas.totalGastado(fecha(p, "desde", hoy), fechaHasta(p, hoy),
                        filtros(p));
                case "gastoPorCategoria" -> herramientas.gastoPorCategoria(fecha(p, "desde", hoy), fechaHasta(p, hoy));
                case "compararPeriodos" -> {
                    LocalDate desdeA = fecha(p, "desdeA", hoy);
                    LocalDate hastaA = rango(desdeA, fecha(p, "hastaA", hoy), hoy);
                    LocalDate desdeB = fecha(p, "desdeB", hoy);
                    LocalDate hastaB = rango(desdeB, fecha(p, "hastaB", hoy), hoy);
                    yield herramientas.compararPeriodos(desdeA, hastaA, desdeB, hastaB);
                }
                case "listarMovimientos" -> herramientas.listarMovimientos(fecha(p, "desde", hoy), fechaHasta(p, hoy),
                        filtros(p), p.path("limite").asInt(HerramientasConsulta.LIMITE_MOVIMIENTOS));
                case "buscarProducto" -> herramientas.buscarProducto(texto(p, "texto"), fecha(p, "desde", hoy),
                        fechaHasta(p, hoy));
                case "ingresosYDisponible" -> herramientas.ingresosYDisponible(mes(p, hoy));
                case "recibosSueldo" -> recibos.resumen(fecha(p, "desde", hoy), fechaHasta(p, hoy));
                default -> throw new HerramientasConsulta.HerramientaInvalida("No existe la herramienta " + nombre);
            };
            resultados.add(resultado);
            return new ClienteIa.ResultadoHerramienta(json.writeValueAsString(resultado), false);
        } catch (HerramientasConsulta.HerramientaInvalida e) {
            return new ClienteIa.ResultadoHerramienta(e.getMessage(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo pasar a JSON el resultado de " + nombre, e);
        }
    }

    /** "desde" o "hasta" en AAAA-MM-DD, una fecha real entre el 2000 y hoy. */
    static LocalDate fecha(JsonNode p, String campo, LocalDate hoy) {
        String texto = texto(p, campo);
        LocalDate dia;
        try {
            dia = LocalDate.parse(texto);
        } catch (DateTimeException e) {
            throw new HerramientasConsulta.HerramientaInvalida("La fecha " + campo + " \"" + texto
                    + "\" no es una fecha real en formato AAAA-MM-DD");
        }
        if (dia.isBefore(FECHA_MINIMA)) {
            throw new HerramientasConsulta.HerramientaInvalida("La fecha " + campo + " es anterior al 2000");
        }
        if (campo.startsWith("desde") && dia.isAfter(hoy)) {
            throw new HerramientasConsulta.HerramientaInvalida("La fecha " + campo + " (" + dia
                    + ") es futura: hoy es " + hoy);
        }
        return dia;
    }

    private static LocalDate fechaHasta(JsonNode p, LocalDate hoy) {
        return rango(fecha(p, "desde", hoy), fecha(p, "hasta", hoy), hoy);
    }

    /** Valida el rango: desde no puede ser posterior a hasta, y como mucho cinco años. */
    static LocalDate rango(LocalDate desde, LocalDate hasta, LocalDate hoy) {
        if (desde.isAfter(hasta)) {
            throw new HerramientasConsulta.HerramientaInvalida("desde (" + desde + ") es posterior a hasta (" + hasta
                    + ")");
        }
        if (ChronoUnit.DAYS.between(desde, hasta) > DIAS_MAXIMOS) {
            throw new HerramientasConsulta.HerramientaInvalida("El rango es de más de cinco años");
        }
        return hasta;
    }

    private static YearMonth mes(JsonNode p, LocalDate hoy) {
        String texto = p.path("mes").asText("");
        if (texto.isBlank()) {
            return YearMonth.from(hoy);
        }
        try {
            YearMonth mes = YearMonth.parse(texto.strip());
            if (mes.isAfter(YearMonth.from(hoy)) || mes.atDay(1).isBefore(FECHA_MINIMA)) {
                throw new HerramientasConsulta.HerramientaInvalida("El mes " + mes + " está fuera de rango");
            }
            return mes;
        } catch (DateTimeException e) {
            throw new HerramientasConsulta.HerramientaInvalida("El mes \"" + texto + "\" no es AAAA-MM");
        }
    }

    private static HerramientasConsulta.Filtros filtros(JsonNode p) {
        return new HerramientasConsulta.Filtros(opcional(p, "categoria"), opcional(p, "medioPago"),
                opcional(p, "cuenta"), opcional(p, "comercio"));
    }

    private static String texto(JsonNode p, String campo) {
        String valor = opcional(p, campo);
        if (valor == null) {
            throw new HerramientasConsulta.HerramientaInvalida("Falta el parámetro " + campo);
        }
        return valor;
    }

    private static String opcional(JsonNode p, String campo) {
        JsonNode valor = p == null ? null : p.get(campo);
        return valor == null || valor.isNull() || valor.asText().isBlank() ? null : valor.asText().strip();
    }

    // --- Definiciones para la IA ---

    static List<ClienteIa.Herramienta> definiciones() {
        Map<String, Object> fecha = Map.of("type", "string", "description", "Fecha AAAA-MM-DD, incluida");
        return List.of(
                new ClienteIa.Herramienta("totalGastado",
                        "Cuánto gastó entre dos fechas y en cuántos gastos, con filtros opcionales.",
                        propiedades("desde", fecha, "hasta", fecha,
                                "categoria", texto("Nombre exacto de una categoría"),
                                "medioPago", texto("Nombre exacto de un medio de pago"),
                                "cuenta", texto("Nombre exacto de una cuenta"),
                                "comercio", texto("Parte del nombre del comercio o la descripción, por ejemplo Día")),
                        List.of("desde", "hasta")),
                new ClienteIa.Herramienta("gastoPorCategoria",
                        "El gasto entre dos fechas repartido por categoría, de mayor a menor.",
                        propiedades("desde", fecha, "hasta", fecha), List.of("desde", "hasta")),
                new ClienteIa.Herramienta("compararPeriodos",
                        "Compara el gasto de dos períodos (A contra B): totales, diferencia, variación y por "
                                + "categoría.",
                        propiedades("desdeA", fecha, "hastaA", fecha, "desdeB", fecha, "hastaB", fecha),
                        List.of("desdeA", "hastaA", "desdeB", "hastaB")),
                new ClienteIa.Herramienta("listarMovimientos",
                        "Los gastos entre dos fechas, los más nuevos primero (hasta 20), con filtros opcionales.",
                        propiedades("desde", fecha, "hasta", fecha,
                                "categoria", texto("Nombre exacto de una categoría"),
                                "medioPago", texto("Nombre exacto de un medio de pago"),
                                "cuenta", texto("Nombre exacto de una cuenta"),
                                "comercio", texto("Parte del nombre del comercio o la descripción"),
                                "limite", Map.of("type", "integer", "description", "Cuántos listar, de 1 a 20")),
                        List.of("desde", "hasta")),
                new ClienteIa.Herramienta("buscarProducto",
                        "Cuántas unidades de un producto compró y cuánto gastó, según el detalle de sus tickets.",
                        propiedades("texto", texto("Parte del nombre del producto, por ejemplo Seven Up"),
                                "desde", fecha, "hasta", fecha),
                        List.of("texto", "desde", "hasta")),
                new ClienteIa.Herramienta("ingresosYDisponible",
                        "Los ingresos de un mes (sueldo, aguinaldo, extras), lo gastado, lo disponible y qué parte "
                                + "de los ingresos se gastó.",
                        propiedades("mes", texto("Mes AAAA-MM; si no se dice, el actual")),
                        List.of()),
                new ClienteIa.Herramienta("recibosSueldo",
                        "Los recibos de sueldo guardados entre dos fechas (por mes del período): bruto, descuentos y "
                                + "neto de cada uno, los totales, lo descontado por cada concepto (por ejemplo el impuesto a "
                                + "las ganancias) y cuánto cambió el neto del primer al último sueldo mensual.",
                        propiedades("desde", fecha, "hasta", fecha), List.of("desde", "hasta")));
    }

    private static Map<String, Object> texto(String descripcion) {
        return Map.of("type", "string", "description", descripcion);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> propiedades(Object... pares) {
        Map<String, Map<String, Object>> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put((String) pares[i], (Map<String, Object>) pares[i + 1]);
        }
        return mapa;
    }

    // --- Tabla de rangos ---

    /** Los rangos más comunes, ya calculados por Java, para que la IA no haga cuentas con fechas. */
    static String rangos(LocalDate hoy) {
        LocalDate lunes = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        YearMonth mes = YearMonth.from(hoy);
        List<String> rangos = new ArrayList<>();
        rangos.add("  - hoy: " + hoy + " a " + hoy);
        rangos.add("  - ayer: " + hoy.minusDays(1) + " a " + hoy.minusDays(1));
        rangos.add("  - esta semana (lunes a hoy): " + lunes + " a " + hoy);
        rangos.add("  - la semana pasada: " + lunes.minusWeeks(1) + " a " + lunes.minusDays(1));
        rangos.add("  - este mes (hasta hoy): " + mes.atDay(1) + " a " + hoy);
        rangos.add("  - el mes pasado: " + mes.minusMonths(1).atDay(1) + " a " + mes.minusMonths(1).atEndOfMonth());
        for (int i = 2; i <= 12; i++) {
            YearMonth m = mes.minusMonths(i);
            rangos.add("  - " + MES.format(m) + ": " + m.atDay(1) + " a " + m.atEndOfMonth());
        }
        return String.join("\n", rangos);
    }

    private static String nombres(List<? extends ConPalabrasClave> opciones) {
        return opciones.stream().map(ConPalabrasClave::getNombre).collect(Collectors.joining(", "));
    }
}
