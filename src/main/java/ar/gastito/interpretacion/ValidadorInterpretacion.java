package ar.gastito.interpretacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.Categoria;
import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.catalogo.MedioPago;
import ar.gastito.catalogo.TarjetaCredito;
import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.Confianza;
import ar.gastito.movimiento.MovimientoInterpretado;

/**
 * Controla la respuesta de la IA contra la base antes de mostrarla:
 * <ul>
 * <li>Candidatas: se descartan las que no existen y las repetidas, hasta {@value #MAXIMO_CANDIDATAS}.
 * Si no queda ninguna, Otros con confianza baja.</li>
 * <li>Solo con confianza alta (y si la primera candidata existe) la categoría queda elegida; si no, se pregunta.</li>
 * <li>Medio de pago o cuenta que no existen: vacío.</li>
 * <li>Pago con tarjeta de crédito: se asocia la tarjeta y no la cuenta (la cuenta paga el resumen).</li>
 * <li>Monto ausente, inválido o no positivo: queda vacío y se pide en la tarjeta.</li>
 * </ul>
 */
@Component
public class ValidadorInterpretacion {

    static final int LARGO_MAXIMO_DESCRIPCION = 255;
    static final int MAXIMO_CANDIDATAS = 3;

    private final ResolvedorFechas fechas;

    public ValidadorInterpretacion(ResolvedorFechas fechas) {
        this.fechas = fechas;
    }

    public sealed interface Resultado {
    }

    /**
     * El movimiento puede venir sin monto o sin categoría elegida: se completa en la tarjeta.
     *
     * @param juntada si pagó por un grupo (el monto es el total), o null
     * @param cuotas  si es una compra en cuotas (el monto es el total), o null
     */
    public record Valida(MovimientoInterpretado movimiento, Confianza confianza, PedidoJuntada juntada,
            PedidoCuotas cuotas) implements Resultado {

        public Valida(MovimientoInterpretado movimiento, Confianza confianza) {
            this(movimiento, confianza, null, null);
        }
    }

    /**
     * Alguien me devolvió plata ("Pablo me pasó 8000"): una DEVOLUCION, no un ingreso.
     *
     * @param monto   null si no se dijo cuánto
     * @param persona quién, o null
     * @param de      de qué juntada ("el asado"), o null
     * @param cuenta  dónde entró, o null
     */
    public record Devolucion(BigDecimal monto, String persona, String de, Cuenta cuenta, MedioPago medio,
            LocalDateTime fechaHora) implements Resultado {
    }

    /** "Agregá un fijo: …": el alta de un gasto fijo, para mostrar en su tarjeta antes de guardarlo. */
    public record AltaFijo(PedidoFijo pedido) implements Resultado {
    }

    public record NoEsGasto() implements Resultado {
    }

    /** Una pregunta sobre sus gastos: la responde ConsultaService. */
    public record Pregunta() implements Resultado {
    }

    /**
     * No está claro qué quiere: se le pregunta con botones entre las opciones (2 o 3, la más probable primero). Si
     * elige gasto, se usa este.
     */
    public record Dudosa(Valida comoGasto, List<Intencion> opciones) implements Resultado {

        public Dudosa(Valida comoGasto) {
            this(comoGasto, OPCIONES_POR_DEFECTO);
        }
    }

    /** Si la IA no dijo entre qué duda: un gasto o una pregunta, como siempre. */
    static final List<Intencion> OPCIONES_POR_DEFECTO = List.of(Intencion.GASTO, Intencion.PREGUNTA);
    static final int MAXIMO_OPCIONES = 3;

    /**
     * "Cobré el sueldo, 980 mil en Galicia". Sin cuenta, IngresoService usa la del último ingreso de esa clase.
     *
     * @param monto null si no se dijo
     */
    public record Ingreso(ClaseIngreso clase, BigDecimal monto, String descripcion, Cuenta cuenta, boolean efectivo,
            LocalDateTime fechaHora) implements Resultado {
    }

    /** Qué dato de lo último cargado se corrige. */
    public enum Campo {
        MONTO, DESCRIPCION, FECHA, CATEGORIA, MEDIO
    }

    /**
     * "El último era 3500, no 3700": solo viene el valor del campo que se corrige.
     *
     * @param campo null si no se entendió qué cambiar (o el valor nuevo no es válido)
     */
    public record Correccion(Campo campo, BigDecimal monto, String descripcion, LocalDateTime fechaHora,
            Categoria categoria, MedioPago medio) implements Resultado {
    }

    /** "Borrá lo último que cargué". */
    public record Borrar() implements Resultado {
    }

    /** "Este mes hice 10 horas al 50…": las horas, normalizadas por la IA ("10 al 50, 30 nocturnas"). */
    public record CalculoSueldo(String horas) implements Resultado {
    }

    /** Las listas y resúmenes que también tienen un comando. */
    public enum Lista {
        FIJOS, DEUDAS, CUOTAS, COSTOS_IA, RESUMEN_SEMANA, RESUMEN_MES, HOY, MES, CALCULO, DASHBOARD, AYUDA;

        static Optional<Lista> de(String texto) {
            if (texto == null || texto.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(texto.strip().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** "¿Qué fijos me faltan?", "mandame el resumen de la semana". */
    public record Ver(Lista lista) implements Resultado {
    }

    /** "Cancelá las cuotas de las zapatillas": cuál, como lo dijo (o null). */
    public record CancelarCuotas(String cual) implements Resultado {
    }

    /** "Lo de Pablo dalo por perdido". */
    public record Perdonar(String persona, String de) implements Resultado {
    }

    /** "Sacá Netflix de los fijos": cuál, como lo dijo (o null). */
    public record BajaFijo(String cual) implements Resultado {
    }

    public Resultado validar(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        Intencion intencion = ia == null ? Intencion.OTRO : Intencion.de(ia.intencion());
        return switch (intencion) {
            case OTRO -> new NoEsGasto();
            case PREGUNTA -> new Pregunta();
            case GASTO -> comoGasto(ia, catalogo, ahora);
            case DUDOSO -> new Dudosa(comoGasto(ia, catalogo, ahora), opciones(ia.opciones()));
            case DEVOLUCION -> new Devolucion(monto(ia.monto()).orElse(null), texto(ia.devolucionPersona()),
                    texto(ia.devolucionDe()), buscar(ia.cuenta(), catalogo.cuentas()).orElse(null),
                    buscar(ia.medioPago(), catalogo.mediosPago()).orElse(null),
                    fechas.resolver(ia.fecha(), ia.hora(), ahora));
            case ALTA_FIJO -> new AltaFijo(comoFijo(ia, catalogo, ahora));
            case INGRESO -> comoIngreso(ia, catalogo, ahora);
            case CORREGIR -> comoCorreccion(ia, catalogo, ahora);
            case BORRAR -> new Borrar();
            case CALCULO_SUELDO -> new CalculoSueldo(Optional.ofNullable(texto(ia.horas())).orElse(""));
            case CANCELAR_CUOTAS -> new CancelarCuotas(texto(ia.descripcion()));
            case PERDONAR -> new Perdonar(texto(ia.devolucionPersona()), texto(ia.devolucionDe()));
            case BAJA_FIJO -> new BajaFijo(texto(ia.descripcion()));
            case VER -> Lista.de(ia.ver()).<Resultado>map(Ver::new).orElseGet(Pregunta::new);
        };
    }

    /** Las que propuso la IA que sirven para preguntar (sin repetir, hasta 3); si son menos de 2, las de siempre. */
    static List<Intencion> opciones(List<String> propuestas) {
        if (propuestas == null) {
            return OPCIONES_POR_DEFECTO;
        }
        List<Intencion> opciones = propuestas.stream().filter(p -> p != null && !p.isBlank()).map(Intencion::de)
                .filter(i -> i != Intencion.OTRO && i != Intencion.DUDOSO).distinct().limit(MAXIMO_OPCIONES)
                .toList();
        return opciones.size() < 2 ? OPCIONES_POR_DEFECTO : opciones;
    }

    private Ingreso comoIngreso(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        ClaseIngreso clase;
        try {
            clase = ClaseIngreso.valueOf(Textos.normalizar(String.valueOf(ia.claseIngreso())).toUpperCase());
        } catch (IllegalArgumentException e) {
            clase = ClaseIngreso.EXTRA;
        }
        MedioPago medio = buscar(ia.medioPago(), catalogo.mediosPago()).orElse(null);
        return new Ingreso(clase, monto(ia.monto()).orElse(null),
                clase == ClaseIngreso.EXTRA ? texto(ia.descripcion()) : null,
                buscar(ia.cuenta(), catalogo.cuentas()).orElse(null), medio != null && medio.isEfectivo(),
                fechas.resolver(ia.fecha(), ia.hora(), ahora));
    }

    /**
     * El campo que dijo la IA con su valor nuevo. Si no dijo el campo, el primer dato que vino (monto, medio,
     * categoría, fecha, descripción). Un valor que no existe o no es válido deja el campo en null.
     */
    private Correccion comoCorreccion(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        Optional<BigDecimal> monto = monto(ia.monto());
        Optional<MedioPago> medio = buscar(ia.medioPago(), catalogo.mediosPago());
        Optional<Categoria> categoria = ia.categorias() == null || ia.categorias().isEmpty() ? Optional.empty()
                : buscar(ia.categorias().get(0), catalogo.categorias());
        String fecha = texto(ia.fecha());
        String descripcion = texto(ia.descripcion());
        Campo campo;
        try {
            campo = Campo.valueOf(Textos.normalizar(String.valueOf(ia.campo())).toUpperCase());
        } catch (IllegalArgumentException e) {
            campo = monto.isPresent() ? Campo.MONTO : medio.isPresent() ? Campo.MEDIO
                    : categoria.isPresent() ? Campo.CATEGORIA : fecha != null ? Campo.FECHA
                            : descripcion != null ? Campo.DESCRIPCION : null;
        }
        if (campo == null) {
            return new Correccion(null, null, null, null, null, null);
        }
        return switch (campo) {
            case MONTO -> monto.map(m -> new Correccion(Campo.MONTO, m, null, null, null, null))
                    .orElseGet(() -> new Correccion(null, null, null, null, null, null));
            case MEDIO -> medio.map(m -> new Correccion(Campo.MEDIO, null, null, null, null, m))
                    .orElseGet(() -> new Correccion(null, null, null, null, null, null));
            case CATEGORIA -> categoria.map(c -> new Correccion(Campo.CATEGORIA, null, null, null, c, null))
                    .orElseGet(() -> new Correccion(null, null, null, null, null, null));
            case FECHA -> fecha == null ? new Correccion(null, null, null, null, null, null)
                    : new Correccion(Campo.FECHA, null, null, fechas.resolver(fecha, ia.hora(), ahora), null, null);
            case DESCRIPCION -> descripcion == null ? new Correccion(null, null, null, null, null, null)
                    : new Correccion(Campo.DESCRIPCION, null, descripcion(descripcion, catalogo.otros()), null,
                            null, null);
        };
    }

    /**
     * Los datos del gasto (nombre, monto, categoría, medio) se controlan igual que en un gasto; el día tiene que ser
     * del 1 al 31 (si no, queda vacío y se pregunta). La luz, el gas y el agua son variables aunque no se diga.
     */
    private PedidoFijo comoFijo(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        Valida v = comoGasto(ia, catalogo, ahora);
        MovimientoInterpretado m = v.movimiento();
        Integer dia = null;
        String d = texto(ia.fijoDia());
        if (d != null && d.matches("\\d{1,2}") && Integer.parseInt(d) >= 1 && Integer.parseInt(d) <= 31) {
            dia = Integer.parseInt(d);
        }
        boolean bimestral = "BIMESTRAL".equalsIgnoreCase(texto(ia.fijoFrecuencia()));
        boolean variable = Textos.normalizar(String.valueOf(texto(ia.fijoVariable()))).equals("si")
                || PedidoFijo.esVariable(m.descripcion());
        return new PedidoFijo(m.descripcion(), m.monto(), m.categoria(), m.candidatas(), m.medioPago(), m.cuenta(),
                m.tarjeta(), dia, bimestral, variable, texto(ia.fijoAlias()));
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    private Valida comoGasto(InterpretacionIa ia, Catalogo catalogo, LocalDateTime ahora) {
        Candidatas c = candidatas(ia.categorias(), ia.confianza(), catalogo);
        List<Categoria> candidatas = c.categorias();
        Confianza confianza = c.confianza();

        MedioPago medio = buscar(ia.medioPago(), catalogo.mediosPago()).orElse(null);
        Cuenta cuenta = buscar(ia.cuenta(), catalogo.cuentas()).orElse(null);
        TarjetaCredito tarjeta = null;
        if (medio != null && medio.esTarjetaDeCredito()) {
            tarjeta = catalogo.tarjetaCredito().orElse(null);
            cuenta = null;
        }
        String descripcion = descripcion(ia.descripcion(), candidatas.get(0));
        LocalDateTime fechaHora = fechas.resolver(ia.fecha(), ia.hora(), ahora);
        BigDecimal monto = monto(ia.monto()).orElse(null);

        PedidoCuotas cuotas = PedidoCuotas.de(ia.cuotasCantidad(), ia.cuotasValorCuota(), monto);
        if (cuotas != null) {
            monto = cuotas.total();
            if (tarjeta == null) {
                tarjeta = catalogo.tarjetaCredito().orElse(null);
                medio = catalogo.mediosPago().stream().filter(MedioPago::esTarjetaDeCredito).findFirst()
                        .orElse(medio);
                cuenta = null;
            }
        }

        MovimientoInterpretado movimiento = confianza == Confianza.ALTA
                ? MovimientoInterpretado.conCategoria(monto, descripcion, candidatas.get(0), medio, cuenta, tarjeta,
                        fechaHora)
                : new MovimientoInterpretado(monto, descripcion, null, candidatas, medio, cuenta, tarjeta, fechaHora);
        return new Valida(movimiento, confianza, PedidoJuntada.de(ia.juntadaPersonas(), ia.juntadaNombres()), cuotas);
    }

    /**
     * Las categorías que propuso la IA que existen, sin repetir y en orden (al menos una).
     *
     * @param confianza ya corregida: baja si no quedó ninguna o si la primera propuesta no existe
     */
    public record Candidatas(List<Categoria> categorias, Confianza confianza) {
    }

    public static Candidatas candidatas(List<String> nombres, String confianzaIa, Catalogo catalogo) {
        List<Categoria> candidatas = new ArrayList<>();
        boolean primeraExiste = false;
        List<String> propuestas = nombres == null ? List.of() : nombres;
        for (int i = 0; i < propuestas.size() && candidatas.size() < MAXIMO_CANDIDATAS; i++) {
            Optional<Categoria> c = buscar(propuestas.get(i), catalogo.categorias());
            if (i == 0) {
                primeraExiste = c.isPresent();
            }
            c.filter(categoria -> !candidatas.contains(categoria)).ifPresent(candidatas::add);
        }
        Confianza confianza = confianza(confianzaIa);
        if (candidatas.isEmpty()) {
            candidatas.add(catalogo.otros());
            confianza = Confianza.BAJA;
        } else if (confianza == Confianza.ALTA && !primeraExiste) {
            confianza = Confianza.BAJA;
        }
        return new Candidatas(List.copyOf(candidatas), confianza);
    }

    static Confianza confianza(String texto) {
        if (texto == null) {
            return Confianza.BAJA;
        }
        return switch (Textos.normalizar(texto)) {
            case "alta" -> Confianza.ALTA;
            case "media" -> Confianza.MEDIA;
            default -> Confianza.BAJA;
        };
    }

    /** Un importe en pesos: "1500", "1500.50" o a la argentina ("1.500,50"). Vacío si no es positivo. */
    public static Optional<BigDecimal> monto(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String limpio = texto.trim().replace("$", "").replace(" ", "");
        Optional<BigDecimal> monto = Montos.parsear(limpio);
        if (monto.isEmpty()) {
            try {
                monto = Optional.of(new BigDecimal(limpio));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return monto.filter(m -> m.signum() > 0).map(m -> m.setScale(2, RoundingMode.HALF_UP));
    }

    private static String descripcion(String texto, Categoria categoria) {
        if (texto == null || texto.isBlank()) {
            return categoria.getNombre();
        }
        String d = Textos.capitalizar(texto.trim());
        return d.length() > LARGO_MAXIMO_DESCRIPCION ? d.substring(0, LARGO_MAXIMO_DESCRIPCION) : d;
    }

    /** Solo por nombre exacto (sin tildes ni mayúsculas), sin palabras clave. */
    public static <T extends ConPalabrasClave> Optional<T> buscarPorNombre(String nombre, List<T> opciones) {
        if (nombre == null || nombre.isBlank()) {
            return Optional.empty();
        }
        String buscado = Textos.normalizar(nombre);
        return opciones.stream().filter(o -> Textos.normalizar(o.getNombre()).equals(buscado)).findFirst();
    }

    /** Primero por nombre exacto (sin tildes ni mayúsculas), después por palabra clave. */
    public static <T extends ConPalabrasClave> Optional<T> buscar(String nombre, List<T> opciones) {
        Optional<T> exacto = buscarPorNombre(nombre, opciones);
        if (exacto.isPresent() || nombre == null || nombre.isBlank()) {
            return exacto;
        }
        String buscado = Textos.normalizar(nombre);
        List<T> porPalabra = opciones.stream()
                .filter(o -> Textos.palabrasClave(o).contains(buscado))
                .toList();
        return porPalabra.size() == 1 ? Optional.of(porPalabra.get(0)) : Optional.empty();
    }
}
