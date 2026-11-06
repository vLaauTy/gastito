package ar.gastito.inversion;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ar.gastito.catalogo.ConPalabrasClave;
import ar.gastito.catalogo.Cuenta;
import ar.gastito.interpretacion.Montos;

/**
 * Los pedidos de inversiones, sin IA, dichos como sea ("tengo 850.000 invertidos en Cocos, 500.000 en CEDEAR SPY y
 * 350.000 en un FCI", "pasé 200.000 de Galicia a Cocos", "compré 100.000 de SPY", "Cocos vale 870.000", "reservá
 * 300.000 de Cocos para Bariloche", "¿cuánto tengo invertido?"). Se reconocen por los nombres de las cuentas de
 * inversión y sus posiciones: un mensaje que no nombra ninguna (ni habla de lo invertido) no es de inversiones. Lo
 * que falta (de dónde salió un aporte, a dónde fue un retiro) lo pregunta el bot con botones.
 */
public final class ParserInversion {

    /** Las cuentas y posiciones contra las que se reconoce el mensaje. */
    public record Contexto(List<Cuenta> inversiones, List<PosicionInversion> posiciones, List<Cuenta> bancarias) {
    }

    public sealed interface Pedido {
    }

    /** "¿Cuánto tengo invertido?". */
    public record Consulta() implements Pedido {
    }

    /**
     * El ahorro previo de una cuenta (existente o nueva) y sus posiciones.
     *
     * @param cuenta      la cuenta, si ya existe
     * @param nombreCuenta el nombre como lo dijo (para crearla), si no existe
     * @param total       el total de la cuenta, o null si solo dijo las posiciones
     */
    public record SaldoInicial(Cuenta cuenta, String nombreCuenta, BigDecimal total,
            List<InversionService.SaldoPedido> posiciones) implements Pedido {
    }

    /** El ahorro previo de varias cuentas en un mismo mensaje: se confirman y se guardan juntas. */
    public record SaldosIniciales(List<SaldoInicial> cuentas) implements Pedido {
    }

    /**
     * Plata que entra a una cuenta de inversión (y, si dijo una posición, a esa posición).
     *
     * @param posicionNueva el nombre de una posición que todavía no existe, o null
     * @param desde         de qué cuenta salió, o null
     * @param efectivo      si salió del efectivo
     */
    public record Aporte(Cuenta cuenta, PosicionInversion posicion, String posicionNueva, BigDecimal monto,
            Cuenta desde, boolean efectivo) implements Pedido {

        public boolean origenDicho() {
            return desde != null || efectivo;
        }
    }

    /** Plata que sale de una cuenta de inversión (desde una posición, si se dijo). */
    public record Retiro(Cuenta cuenta, PosicionInversion posicion, BigDecimal monto, Cuenta hacia, boolean efectivo)
            implements Pedido {

        public boolean destinoDicho() {
            return hacia != null || efectivo;
        }
    }

    /** De la bolsa a una posición ("compré 100.000 de CEDEAR SPY"). */
    public record Compra(Cuenta cuenta, PosicionInversion posicion, String posicionNueva, BigDecimal monto)
            implements Pedido {
    }

    /** De una posición a la bolsa ("vendí 50.000 de SPY", "rescaté el FCI"). */
    public record Venta(Cuenta cuenta, PosicionInversion posicion, BigDecimal monto) implements Pedido {
    }

    /** "Cocos vale 870.000" (el total de la cuenta) o "SPY está en 520.000" (una posición). */
    public record Valuacion(Cuenta cuenta, PosicionInversion posicion, BigDecimal valor) implements Pedido {
    }

    /** "Reservá 300.000 de Cocos para Bariloche". */
    public record Reserva(Cuenta cuenta, String meta, BigDecimal monto) implements Pedido {
    }

    /** "Liberá la reserva de Bariloche". */
    public record LiberarReserva(String meta) implements Pedido {
    }

    private static final Pattern MONTO = Pattern.compile(
            "(?<![\\w.,/])\\$?\\s?(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)"
                    + "(?:\\s?(mil|k|lucas|millones|millon|palos|m)(?![a-z]))?(?![\\w/])");
    private static final Pattern CONSULTA = Pattern.compile(
            "^(?:y\\s+)?(?:cuanto|que)\\s+(?:plata\\s+)?(?:tengo|hay|llevo)\\s+(?:invertid[oa]s?|en\\s+(?:las\\s+|mis\\s+)?inversiones|ahorrad[oa]s?)"
                    + "|^(?:como|cuanto)\\s+(?:van|vienen|estan|valen|rinden)\\s+(?:mis\\s+|las\\s+)?inversiones"
                    + "|^(?:mis\\s+|las\\s+)?inversiones$|^(?:ver|mostrame|pasame)\\s+(?:mis\\s+|las\\s+)?inversiones");
    private static final Pattern LIBERAR = Pattern.compile(
            "\\b(?:libera|liberar|liberame|saca|cancela|borra|quita)\\w*\\s+(?:la\\s+)?reserva\\s+(?:de|para|del)\\s+(?:el\\s+|la\\s+|los\\s+)?");
    private static final Pattern RESERVA = Pattern.compile("\\b(?:reserva|reservar|reservame|aparta|apartar|separa|separar)\\b");
    private static final Pattern PARA = Pattern.compile("\\bpara\\s+(?:el\\s+|la\\s+|los\\s+|las\\s+|mi\\s+)?");
    private static final Pattern SALDO_INICIAL = Pattern.compile(
            "\\b(?:tengo|tenia|ya\\s+tengo|hay)\\b.*\\b(?:invertid[oa]s?|ahorrad[oa]s?)\\b|\\bsaldo\\s+inicial\\b");
    private static final Pattern VALUACION = Pattern.compile(
            "\\b(?:vale|valen|valia|esta\\s+en|estan\\s+en|esta\\s+a|quedo\\s+en|quedo|cotiza|cerro\\s+en|marca|"
                    + "hoy\\s+tiene|tiene|da)\\b");
    private static final Pattern COMPRA = Pattern.compile("\\b(?:compre|compro|compramos|comprar)\\b");
    private static final Pattern VENTA = Pattern.compile("\\b(?:vendi|vendo|vender|rescate|rescato|rescatar)\\b");
    private static final Pattern APORTE = Pattern.compile(
            "\\b(?:puse|meti|deposite|aporte|ingrese|inverti|cargue|agregue|sume|mande|pase|transferi|movi)\\b");
    private static final Pattern RETIRO = Pattern.compile("\\b(?:saque|retire|traje|extraje|baje|rescate)\\b");
    private static final Pattern EFECTIVO = Pattern.compile("\\b(?:efectivo|cash|en\\s+mano|billetes)\\b");
    private static final Pattern ORIGEN = Pattern.compile(
            "\\b(?:de|desde|del)\\s+(?:la\\s+|el\\s+|mi\\s+)?(?:cuenta\\s+(?:de\\s+|del\\s+)?)?$");
    private static final Pattern DESTINO = Pattern.compile(
            "\\b(?:a|al|hacia|en|para)\\s+(?:la\\s+|el\\s+|mi\\s+)?(?:cuenta\\s+(?:de\\s+|del\\s+)?)?$");
    private static final Pattern ETIQUETA_INICIO = Pattern.compile(
            "(?iu)^[\\s,;:.]*(?:y\\s+)?(?:pesos\\s+)?(?:invertid[oa]s?|ahorrad[oa]s?)?\\s*(?:en|de)?\\s+(?:un|una|el|la|los|las)?\\s*");
    private static final Pattern ETIQUETA_FIN = Pattern.compile("(?iu)[\\s,;:.]*(?:\\by\\b|\\be\\b)?[\\s,;:.]*$");

    /**
     * Marca de un término genérico ("fci", "cedear"): nombra a la posición solo si va solo, no seguido de otro nombre
     * ("el FCI" sí; "CEDEAR QQQ" es otra posición).
     */
    private static final String SOLA = "\u0000";
    private static final String DESPUES_DE_SOLA =
            "(?=\\s*$|\\s*[,.;:?!]|\\s+(?:en|de|del|a|al|y|e|para|con|desde|esta|estan|vale|valen|quedo|que|hoy)\\b)";

    private ParserInversion() {
    }

    /** Lo que pide el mensaje, si es de inversiones. */
    public static Optional<Pedido> parsear(String texto, Contexto ctx) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String original = texto.strip();
        String n = base(original);
        String sinSignos = n.replaceAll("[¿?¡!]", "").strip();
        List<Monto> montos = montos(n);
        List<Mencion<Cuenta>> inversiones = menciones(n, ctx.inversiones(), ParserInversion::terminos);
        List<Mencion<PosicionInversion>> posiciones = menciones(n, ctx.posiciones(), p -> terminosPosicion(p, ctx));
        List<Mencion<Cuenta>> bancarias = menciones(n, ctx.bancarias(), ParserInversion::terminos);

        if (montos.isEmpty()) {
            if (CONSULTA.matcher(sinSignos).find()) {
                return Optional.of(new Consulta());
            }
            Matcher liberar = LIBERAR.matcher(n);
            if (liberar.find()) {
                String meta = NombresInversion.limpiar(recorte(original, n, liberar.end(), n.length()));
                return meta.isEmpty() ? Optional.empty() : Optional.of(new LiberarReserva(meta));
            }
            boolean preguntaPorUna = (!inversiones.isEmpty() || !posiciones.isEmpty())
                    && sinSignos.matches("^(?:y\\s+)?(?:cuanto|como)\\s+(?:tengo|hay|vale|va|esta|viene|rinde)\\b.*");
            return preguntaPorUna ? Optional.of(new Consulta()) : Optional.empty();
        }

        if (SALDO_INICIAL.matcher(n).find()) {
            return saldoInicial(original, n, montos, inversiones, ctx);
        }
        if (inversiones.isEmpty() && posiciones.isEmpty()) {
            return Optional.empty();
        }
        Cuenta cuenta = cuentaDe(inversiones, posiciones, ctx);
        if (cuenta == null) {
            return Optional.empty();
        }
        PosicionInversion posicion = posiciones.stream().map(Mencion::cosa)
                .filter(p -> p.getCuenta().getId().equals(cuenta.getId())).findFirst().orElse(null);
        BigDecimal monto = montos.getFirst().valor();

        if (RESERVA.matcher(n).find()) {
            Matcher para = PARA.matcher(n);
            if (!para.find()) {
                return Optional.empty();
            }
            String meta = NombresInversion.limpiar(recorte(original, n, para.end(), n.length()));
            return meta.isEmpty() ? Optional.empty() : Optional.of(new Reserva(cuenta, meta, monto));
        }

        boolean efectivo = EFECTIVO.matcher(n).find();
        Cuenta banco = bancarias.isEmpty() ? null : bancarias.getFirst().cosa();
        boolean otroLado = banco != null || efectivo;
        if (COMPRA.matcher(n).find() && !otroLado) {
            return Optional.of(new Compra(cuenta, posicion, posicion == null ? nombreNuevo(original, n, montos,
                    inversiones) : null, monto));
        }
        if (VENTA.matcher(n).find() && !otroLado && posicion != null) {
            return Optional.of(new Venta(cuenta, posicion, monto));
        }

        Mencion<Cuenta> laInversion = inversiones.isEmpty() ? null : inversiones.getFirst();
        int inicioInversion = laInversion != null ? laInversion.inicio()
                : posiciones.isEmpty() ? -1 : posiciones.getFirst().inicio();
        boolean inversionEsOrigen = inicioInversion >= 0 && ORIGEN.matcher(n.substring(0, inicioInversion)).find();
        boolean inversionEsDestino = inicioInversion >= 0 && DESTINO.matcher(n.substring(0, inicioInversion)).find();
        boolean bancoEsOrigen = !bancarias.isEmpty()
                && ORIGEN.matcher(n.substring(0, bancarias.getFirst().inicio())).find();
        boolean bancoEsDestino = !bancarias.isEmpty()
                && DESTINO.matcher(n.substring(0, bancarias.getFirst().inicio())).find();
        boolean verboRetiro = RETIRO.matcher(n).find() || (VENTA.matcher(n).find() && otroLado);
        boolean verboAporte = APORTE.matcher(n).find() || COMPRA.matcher(n).find();

        boolean esRetiro = inversionEsOrigen || bancoEsDestino || (verboRetiro && !inversionEsDestino && !bancoEsOrigen);
        boolean esAporte = !esRetiro && (inversionEsDestino || bancoEsOrigen || verboAporte);
        if (esRetiro && (verboRetiro || verboAporte || otroLado)) {
            return Optional.of(new Retiro(cuenta, posicion, monto, banco, efectivo && banco == null));
        }
        if (esAporte) {
            return Optional.of(new Aporte(cuenta, posicion, null, monto, banco, efectivo && banco == null));
        }
        if (VALUACION.matcher(n).find() || montos.size() == 1 && n.matches("^[a-z0-9 ]+\\s+\\$?[\\d.,]+.*")) {
            return montos.size() == 1 ? Optional.of(new Valuacion(cuenta, posicion, monto)) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Lo que puede acompañar al monto en la respuesta a "¿cuánto vale hoy Cocos?". */
    private static final Set<String> RELLENO = Set.of("vale", "valen", "esta", "estan", "en", "a", "hoy", "ahora",
            "unos", "unas", "como", "son", "pesos", "quedo", "tiene", "hay", "cerca", "de", "mas", "menos", "o",
            "y", "el", "la", "total", "todo", "ya", "al", "dia", "aprox", "aproximadamente", "masomenos");

    /**
     * La respuesta a "¿cuánto vale hoy Cocos?": un solo monto ("870.000", "vale 870 mil", "Cocos está en 870k"), o
     * vacío si dice otra cosa ("kiosco 1500" es un gasto).
     *
     * @param cuenta la cuenta por la que se preguntó: su nombre también puede ir en la respuesta
     */
    public static Optional<BigDecimal> unMonto(String texto, ConPalabrasClave cuenta) {
        if (texto == null) {
            return Optional.empty();
        }
        String n = base(texto.strip());
        List<Monto> montos = montos(n);
        if (montos.size() != 1) {
            return Optional.empty();
        }
        String resto = NombresInversion.normalizar(n.substring(0, montos.getFirst().inicio()) + " "
                + n.substring(montos.getFirst().fin()));
        if (cuenta != null) {
            for (String t : terminos(cuenta)) {
                resto = (" " + resto + " ").replace(" " + t + " ", " ").strip();
            }
        }
        boolean soloRelleno = resto.isEmpty()
                || Arrays.stream(resto.split(" ")).allMatch(p -> p.isEmpty() || RELLENO.contains(p) || p.equals("$"));
        return soloRelleno ? Optional.of(montos.getFirst().valor()) : Optional.empty();
    }

    /** "Igual", "igual que antes", "lo mismo", "sigue igual". */
    public static boolean esIgualQueAntes(String texto) {
        return texto != null && base(texto.strip()).replaceAll("[.!¡]", "").strip()
                .matches("^(?:(?:esta|sigue|queda|vale)\\s+)?(?:igual(?:\\s+que\\s+antes)?|lo\\s+mismo|no\\s+cambio)$");
    }

    // --- Saldo inicial ---

    private static Optional<Pedido> saldoInicial(String original, String n, List<Monto> montos,
            List<Mencion<Cuenta>> inversiones, Contexto ctx) {
        // Lo que dice después de cada monto: el primero es la cuenta (con el total) y los demás, sus posiciones.
        List<String> etiquetas = new ArrayList<>();
        for (int i = 0; i < montos.size(); i++) {
            int desde = montos.get(i).fin();
            int hasta = i + 1 < montos.size() ? montos.get(i + 1).inicio() : n.length();
            etiquetas.add(etiqueta(recorte(original, n, desde, hasta)));
        }
        // Cada tramo es: una cuenta con su total ("505.133 invertidos en la reserva de Mercado Pago"), una posición de
        // una cuenta dicha ("680.000 en acciones en Cocos") o una posición de la última cuenta ("500.000 en SPY").
        List<Grupo> grupos = new ArrayList<>();
        Mencion<Cuenta> antes = inversiones.stream().filter(m -> m.fin() <= montos.getFirst().inicio()).findFirst()
                .orElse(null);
        if (antes != null) {
            // "Tengo invertidos en Cocos 500.000 en SPY y 350.000 en el FCI": sin total.
            grupos.add(new Grupo(antes.cosa(), null));
        }
        for (int i = 0; i < montos.size(); i++) {
            String etiqueta = etiquetas.get(i);
            BigDecimal monto = montos.get(i).valor();
            if (etiqueta.isEmpty()) {
                return Optional.empty();
            }
            Optional<String[]> posicionYCuenta = posicionEnCuenta(etiqueta, ctx);
            if (posicionYCuenta.isPresent()) {
                Grupo g = grupo(grupos, posicionYCuenta.get()[1], ctx);
                g.pedidos.add(new InversionService.SaldoPedido(nombrePosicion(posicionYCuenta.get()[0], g, ctx), monto));
            } else if (grupos.isEmpty() || existente(etiqueta, ctx).filter(c -> !esDelUltimo(grupos, c)).isPresent()) {
                Grupo g = grupo(grupos, etiqueta, ctx);
                if (g.total != null) {
                    return Optional.empty();
                }
                g.total = monto;
            } else {
                Grupo g = grupos.getLast();
                g.pedidos.add(new InversionService.SaldoPedido(nombrePosicion(etiqueta, g, ctx), monto));
            }
        }
        List<SaldoInicial> saldos = new ArrayList<>();
        for (Grupo g : grupos) {
            // Una cuenta nueva, solo si dijo que es una inversión ("tengo 500.000 ahorrados en el banco" no lo es).
            if (g.cuenta == null && (g.nombre.isEmpty() || g.nombre.length() > 50 || !n.contains("invertid"))) {
                return Optional.empty();
            }
            if (g.total == null && g.pedidos.isEmpty()) {
                continue;
            }
            saldos.add(new SaldoInicial(g.cuenta, g.cuenta == null ? g.nombre : null, g.total, List.copyOf(g.pedidos)));
        }
        if (saldos.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(saldos.size() == 1 ? saldos.getFirst() : new SaldosIniciales(saldos));
    }

    /** Lo que va después de "en" y no es una cuenta: "plazo fijo en dólares", "un FCI en pesos". */
    private static final Set<String> NO_SON_CUENTAS = Set.of("dolares", "dolar", "pesos", "usd", "efectivo",
            "total", "cuotas", "acciones", "cedears", "bonos", "cripto");

    /** Una cuenta del saldo inicial mientras se arma: existente o nueva (por nombre), su total y sus posiciones. */
    private static final class Grupo {
        final Cuenta cuenta;
        final String nombre;
        BigDecimal total;
        final List<InversionService.SaldoPedido> pedidos = new ArrayList<>();

        Grupo(Cuenta cuenta, String nombre) {
            this.cuenta = cuenta;
            this.nombre = cuenta != null ? cuenta.getNombre() : nombre;
        }
    }

    /** El grupo de esa cuenta (por nombre o alias), o uno nuevo al final. */
    private static Grupo grupo(List<Grupo> grupos, String nombre, Contexto ctx) {
        Cuenta c = existente(nombre, ctx).orElse(null);
        String buscado = NombresInversion.normalizar(c != null ? c.getNombre() : nombre);
        for (Grupo g : grupos) {
            if (NombresInversion.normalizar(g.nombre).equals(buscado)) {
                return g;
            }
        }
        Grupo nuevo = new Grupo(c, nombre);
        grupos.add(nuevo);
        return nuevo;
    }

    private static Optional<Cuenta> existente(String nombre, Contexto ctx) {
        String n = NombresInversion.normalizar(nombre);
        return ctx.inversiones().stream().filter(c -> terminos(c).contains(n)).findFirst();
    }

    private static boolean esDelUltimo(List<Grupo> grupos, Cuenta c) {
        Grupo ultimo = grupos.getLast();
        return ultimo.cuenta != null && ultimo.cuenta.getId().equals(c.getId());
    }

    /**
     * "Acciones en Cocos" → posición "Acciones" de la cuenta "Cocos": cuando lo que va después del último "en" es una
     * cuenta de inversión que ya existe o un nombre propio (con mayúscula). "Plazo fijo en dólares" no se separa.
     */
    static Optional<String[]> posicionEnCuenta(String etiqueta, Contexto ctx) {
        Matcher m = Pattern.compile("(?iu)^(.+?)\\s+en\\s+(?:la\\s+|el\\s+)?(\\S.*)$").matcher(etiqueta);
        if (!m.matches()) {
            return Optional.empty();
        }
        String posicion = NombresInversion.limpiar(m.group(1));
        String cuenta = NombresInversion.limpiar(m.group(2));
        String palabra = NombresInversion.normalizar(cuenta);
        boolean unaPalabra = !palabra.contains(" ") && !NO_SON_CUENTAS.contains(palabra);
        boolean esCuenta = existente(cuenta, ctx).isPresent() || Character.isUpperCase(m.group(2).charAt(0))
                || unaPalabra;
        return esCuenta && !posicion.isEmpty() && !cuenta.isEmpty()
                ? Optional.of(new String[] {posicion, cuenta}) : Optional.empty();
    }

    /** El nombre de una posición que ya existe en la cuenta ("SPY" → "CEDEAR SPY"), o el dicho. */
    private static String nombrePosicion(String etiqueta, Grupo g, Contexto ctx) {
        if (g.cuenta == null) {
            return etiqueta;
        }
        List<PosicionInversion> deLaCuenta = ctx.posiciones().stream()
                .filter(p -> p.getCuenta().getId().equals(g.cuenta.getId())).toList();
        List<Mencion<PosicionInversion>> nombradas = menciones(base(etiqueta), deLaCuenta,
                p -> terminosPosicion(p, ctx));
        return deLaCuenta.stream().filter(p -> NombresInversion.mismaPosicion(p, etiqueta)).findFirst()
                .or(() -> nombradas.size() == 1 ? Optional.of(nombradas.getFirst().cosa()) : Optional.empty())
                .map(PosicionInversion::getNombre).orElse(etiqueta);
    }

    /** "invertidos en Cocos, " → "Cocos"; " en un FCI" → "FCI". */
    static String etiqueta(String texto) {
        String t = ETIQUETA_INICIO.matcher(texto).replaceFirst("");
        t = ETIQUETA_FIN.matcher(t).replaceFirst("");
        return NombresInversion.limpiar(t);
    }

    /** "compré 100.000 de CEDEAR QQQ en Cocos" → "CEDEAR QQQ". */
    private static String nombreNuevo(String original, String n, List<Monto> montos, List<Mencion<Cuenta>> cuentas) {
        int desde = montos.getFirst().fin();
        int hasta = cuentas.stream().map(Mencion::inicio).filter(i -> i > desde).min(Comparator.naturalOrder())
                .orElse(n.length());
        String t = recorte(original, n, desde, hasta)
                .replaceFirst("(?iu)^\\s*(?:pesos\\s+)?(?:de|del|en)\\s+", "")
                .replaceFirst("(?iu)\\s+(?:en|de|del|desde)\\s*$", "");
        String nombre = NombresInversion.limpiar(t);
        return nombre.isEmpty() || nombre.length() > PosicionInversion.LARGO_NOMBRE ? null : nombre;
    }

    // --- Cuentas, posiciones y montos ---

    private static Cuenta cuentaDe(List<Mencion<Cuenta>> inversiones, List<Mencion<PosicionInversion>> posiciones,
            Contexto ctx) {
        if (!inversiones.isEmpty()) {
            return inversiones.getFirst().cosa();
        }
        if (!posiciones.isEmpty()) {
            return posiciones.getFirst().cosa().getCuenta();
        }
        return ctx.inversiones().size() == 1 ? ctx.inversiones().getFirst() : null;
    }

    record Monto(BigDecimal valor, int inicio, int fin) {
    }

    record Mencion<T>(T cosa, int inicio, int fin) {
    }

    static List<Monto> montos(String n) {
        List<Monto> montos = new ArrayList<>();
        Matcher m = MONTO.matcher(n);
        while (m.find()) {
            String palabra = m.group(2) == null ? "" : switch (m.group(2)) {
                case "lucas" -> "mil";
                case "palos" -> "millones";
                default -> m.group(2);
            };
            Montos.parsearConPalabras(m.group(1) + (palabra.isEmpty() ? "" : " " + palabra))
                    .ifPresent(v -> montos.add(new Monto(v, m.start(), m.end())));
        }
        return montos;
    }

    /** Las veces que el texto nombra algo, sin superponerse (el término más largo gana), en orden. */
    static <T> List<Mencion<T>> menciones(String n, List<T> cosas, Function<T, List<String>> terminos) {
        record Candidato<T>(T cosa, String termino) {
        }
        List<Candidato<T>> candidatos = new ArrayList<>();
        for (T cosa : cosas) {
            terminos.apply(cosa).forEach(t -> candidatos.add(new Candidato<>(cosa, t)));
        }
        candidatos.sort(Comparator.comparingInt((Candidato<T> c) -> c.termino().length()).reversed());
        List<Mencion<T>> menciones = new ArrayList<>();
        boolean[] usado = new boolean[n.length()];
        for (Candidato<T> c : candidatos) {
            boolean sola = c.termino().startsWith(SOLA);
            String termino = sola ? c.termino().substring(1) : c.termino();
            Matcher m = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(termino)
                    + (sola ? DESPUES_DE_SOLA : "(?![a-z0-9])")).matcher(n);
            while (m.find()) {
                boolean libre = true;
                for (int i = m.start(); i < m.end(); i++) {
                    libre &= !usado[i];
                }
                if (libre) {
                    for (int i = m.start(); i < m.end(); i++) {
                        usado[i] = true;
                    }
                    menciones.add(new Mencion<>(c.cosa(), m.start(), m.end()));
                }
            }
        }
        menciones.sort(Comparator.comparingInt(Mencion::inicio));
        return menciones;
    }

    static List<String> terminos(ConPalabrasClave c) {
        List<String> terminos = new ArrayList<>();
        terminos.add(NombresInversion.normalizar(c.getNombre()));
        if (c.getPalabrasClave() != null) {
            for (String p : c.getPalabrasClave().split(",")) {
                terminos.add(NombresInversion.normalizar(p));
            }
        }
        return terminos.stream().filter(t -> t.length() >= 2).distinct().toList();
    }

    /** El nombre, los alias y una palabra genérica del nombre ("fci") si ninguna otra posición la tiene. */
    private static List<String> terminosPosicion(PosicionInversion p, Contexto ctx) {
        List<String> terminos = new ArrayList<>(terminos(p));
        String distintivo = NombresInversion.palabrasClave(p.getNombre());
        if (distintivo != null) {
            terminos.add(distintivo);
        }
        for (String palabra : NombresInversion.normalizar(p.getNombre()).split(" ")) {
            if (NombresInversion.GENERICAS.contains(palabra) && palabra.length() >= 3
                    && ctx.posiciones().stream().filter(o -> NombresInversion.normalizar(o.getNombre())
                            .matches("(?:.*\\s)?" + Pattern.quote(palabra) + "(?:\\s.*)?")).count() == 1) {
                terminos.add(SOLA + palabra);
            }
        }
        return terminos.stream().distinct().toList();
    }

    /** Minúsculas y sin tildes, del mismo largo que el original (para recortar nombres con sus mayúsculas). */
    static String base(String texto) {
        StringBuilder sb = new StringBuilder(texto.length());
        for (char c : texto.toCharArray()) {
            String sinTilde = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            sb.append(sinTilde.length() == 1 ? sinTilde.toLowerCase(Locale.ROOT).charAt(0) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** El mismo tramo del texto original (con mayúsculas). */
    private static String recorte(String original, String n, int desde, int hasta) {
        return original.length() == n.length() ? original.substring(desde, hasta) : n.substring(desde, hasta);
    }
}
