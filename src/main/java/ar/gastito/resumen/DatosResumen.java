package ar.gastito.resumen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Todo lo que calcula Java para un resumen. Se guarda como JSON en resumen.datos y es lo único que ve la IA: no
 * puede escribir un número que no esté acá (ver {@link ControlNumeros}). La plata va con dos decimales.
 *
 * @param ingresos      solo en el mensual; null en el semanal
 * @param costoIaUsd    solo en el mensual
 * @param pocosDatos    menos gastos o días con gastos que el mínimo: no hay escenarios ni consejo, sino
 *                      {@link #POCOS_DATOS}
 * @param notas         notas al pie del resumen numérico, solo sobre datos que existen en el período (por ejemplo,
 *                      gastos de juntadas cuya devolución todavía no se descuenta). No se le mandan a la IA.
 */
public record DatosResumen(
        Periodo periodo,
        BigDecimal totalGastado,
        int cantidadGastos,
        Comparacion comparacion,
        List<Categoria> porCategoria,
        GastoGrande gastoMasGrande,
        Hormiga hormiga,
        int diasSinGastos,
        SinEspecificar sinEspecificar,
        Productos productos,
        List<Escenario> escenariosAhorro,
        Ingresos ingresos,
        BigDecimal costoIaUsd,
        boolean pocosDatos,
        List<String> notas) {

    /** Lo que va en lugar del consejo cuando hay pocos datos. */
    public static final String POCOS_DATOS = "Todavía hay pocos datos para darte un consejo. Seguí cargando tus gastos.";

    /**
     * Los campos (rutas JSON) que ya muestra la parte numérica del resumen: total, cantidad, variación (y el total
     * anterior del que sale), días sin gastos, gastos hormiga y, en el mensual, ingresos y costo de la IA. Las
     * observaciones de la IA no los repiten.
     */
    public static final List<String> MOSTRADOS = List.of("/totalGastado", "/cantidadGastos", "/comparacion/total",
            "/comparacion/variacionPorcentaje", "/diasSinGastos", "/hormiga/cantidad", "/hormiga/total",
            "/ingresos/total", "/ingresos/disponible", "/ingresos/porcentajeGastado", "/costoIaUsd");

    /**
     * @param hasta último día con datos: en un resumen parcial, hoy
     * @param fin   último día del período
     * @param dias  cuántos días se cuentan (desde el inicio hasta "hasta")
     * @param registroDesde si el registro empezó dentro del período, el día del primer gasto: el período está
     *                      incompleto y no lleva comparaciones ni consejo; null si ya se registraba desde antes
     */
    public record Periodo(String tipo, LocalDate desde, LocalDate hasta, LocalDate fin, boolean parcial, int dias,
            LocalDate registroDesde) {

        public Periodo(String tipo, LocalDate desde, LocalDate hasta, LocalDate fin, boolean parcial, int dias) {
            this(tipo, desde, hasta, fin, parcial, dias, null);
        }
    }

    /** Lleva consejo (y escenarios de ahorro): hay datos suficientes y el registro no empezó a mitad del período. */
    public boolean conConsejo() {
        return !pocosDatos && periodo.registroDesde() == null;
    }

    /**
     * Lo que va en lugar del consejo: el registro empezó a mitad del período, o hay pocos datos. Null si lleva
     * consejo.
     */
    public String sinConsejo() {
        if (periodo.registroDesde() != null) {
            return "Registro desde el " + periodo.registroDesde().format(java.time.format.DateTimeFormatter
                    .ofPattern("dd/MM")) + ": este período está incompleto, así que no lo comparo ni te doy consejos.";
        }
        return pocosDatos ? POCOS_DATOS : null;
    }

    /**
     * Contra el período anterior hasta el mismo punto.
     *
     * @param conDatos            false si el registro empezó después del inicio del período anterior: no hay con
     *                            qué comparar (no es que se gastó $0)
     * @param total               null sin datos
     * @param variacionPorcentaje redondeada; null sin datos o si el anterior fue cero
     */
    public record Comparacion(LocalDate desde, LocalDate hasta, boolean conDatos, BigDecimal total,
            Integer variacionPorcentaje) {
    }

    /**
     * @param totalAnterior       null si el período anterior no tiene datos
     * @param variacionPorcentaje contra la misma categoría en el período anterior; null sin datos o si allá fue cero
     */
    public record Categoria(String nombre, String emoji, BigDecimal total, int cantidad, BigDecimal totalAnterior,
            Integer variacionPorcentaje) {
    }

    public record GastoGrande(String descripcion, String categoria, BigDecimal monto, LocalDate fecha) {
    }

    /** Gastos sin medio de pago ni cuenta: no se pueden repartir por billetera (hay que corregirlos). */
    public record SinEspecificar(int cantidad, BigDecimal total) {
    }

    /**
     * Compras chicas en las categorías hormiga.
     *
     * @param porSemana compras por semana, con un decimal
     */
    public record Hormiga(BigDecimal umbral, List<String> categorias, int cantidad, BigDecimal total,
            BigDecimal promedio, BigDecimal porSemana) {
    }

    /** Del detalle de los tickets: los más comprados por unidades y por plata (sin descuentos). */
    public record Productos(List<Producto> porUnidades, List<Producto> porPlata) {
    }

    public record Producto(String nombre, BigDecimal unidades, BigDecimal total) {
    }

    /**
     * Un ahorro posible, calculado por Java para que la IA lo cite tal cual.
     *
     * @param tipo         HORMIGA, CATEGORIA_QUE_MAS_SUBIO o CATEGORIA_MAS_GRANDE
     * @param descripcion  qué cambiaría, en palabras
     * @param ahorroPeriodo cuánto se habría ahorrado en este período
     * @param ahorroMensual lo mismo llevado a un mes (redondeado a la centena). En el semanal, solo si la categoría
     *                      tiene gastos de al menos 2 semanas; si no, null y el escenario se dice por semana
     */
    public record Escenario(String tipo, String descripcion, String categoria, BigDecimal valorActual,
            BigDecimal valorObjetivo, BigDecimal ahorroPeriodo, BigDecimal ahorroMensual) {
    }

    /** @param porcentajeGastado qué parte de los ingresos se gastó; null sin ingresos */
    public record Ingresos(BigDecimal sueldo, BigDecimal aguinaldo, BigDecimal extras, BigDecimal total,
            BigDecimal disponible, Integer porcentajeGastado) {
    }
}
