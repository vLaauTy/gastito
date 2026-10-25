package ar.gastito.prueba;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import ar.gastito.movimiento.ClaseIngreso;
import ar.gastito.movimiento.ItemTicket;
import ar.gastito.movimiento.Origen;

/**
 * Unos meses de gastos e ingresos inventados pero creíbles, para probar la aplicación sin datos reales. No toca la
 * base: arma la lista, y {@link GeneradorDatosPrueba} la carga. Con la misma semilla sale siempre lo mismo.
 * <ul>
 * <li>De 2 a 5 gastos por día, en todas las categorías y medios de pago, con montos típicos de cada una.</li>
 * <li>Gastos hormiga en el kiosco casi todos los días.</li>
 * <li>Servicios, suscripciones, el seguro y el impuesto de sellos, todos los meses el mismo día.</li>
 * <li>Algunas compras en el Día con el detalle del ticket.</li>
 * <li>El mes del medio es caro (más salidas, ropa y compras grandes), para que las comparaciones muestren algo.</li>
 * <li>Sueldo todos los meses, un aguinaldo y algunos extras.</li>
 * </ul>
 */
public final class PlanDatosPrueba {

    static final int MINIMO_POR_DIA = 2;
    static final int MAXIMO_POR_DIA = 5;
    static final BigDecimal SUELDO = new BigDecimal("1450000");
    /** En el mes caro, los gastos variables suben esto. */
    static final BigDecimal RECARGO_MES_CARO = new BigDecimal("1.35");

    public static final String KIOSCO = "Kiosco";
    public static final String SUPERMERCADO = "Supermercado";
    public static final String SALIDAS = "Salidas";
    public static final String COMIDA = "Comida afuera / delivery";
    public static final String CASA = "Productos para la casa";
    public static final String SERVICIOS = "Servicios (luz, gas, servicios sociales)";
    public static final String TRANSPORTE = "Transporte / Moto";
    public static final String SUSCRIPCIONES = "Suscripciones";
    public static final String SEGUROS = "Seguros";
    public static final String SALUD = "Salud / farmacia";
    public static final String ROPA = "Ropa";
    public static final String IMPUESTOS = "Impuestos y cargos de tarjeta";
    public static final String OTROS = "Otros";

    public static final String EFECTIVO = "Efectivo";
    public static final String DEBITO = "Débito";
    public static final String TARJETA = "Tarjeta de crédito";
    public static final String TRANSFERENCIA = "Transferencia";
    public static final String QR = "QR";

    public static final String GALICIA = "Galicia";
    public static final String MERCADO_PAGO = "Mercado Pago";

    /** Un gasto a cargar. La cuenta sale del medio: débito y transferencia de Galicia, QR de Mercado Pago. */
    public record Gasto(LocalDateTime fechaHora, String categoria, String descripcion, BigDecimal monto, String medio,
            String cuenta, Origen origen, Ticket ticket) {
    }

    /** El detalle de un ticket del Día: productos y descuentos, en el orden impreso. */
    public record Ticket(String comercio, String sucursal, BigDecimal subtotal, BigDecimal descuentos,
            List<ItemTicket> items) {
    }

    /** @param cuenta null si fue en efectivo */
    public record Ingreso(ClaseIngreso clase, BigDecimal monto, String descripcion, String cuenta, LocalDate fecha) {
    }

    public record Plan(LocalDate desde, LocalDate hasta, YearMonth mesCaro, List<Gasto> gastos,
            List<Ingreso> ingresos) {
    }

    /** Un gasto fijo: todos los meses el mismo día. */
    private record Fijo(int dia, String categoria, String descripcion, int monto, String medio) {
    }

    private static final List<Fijo> FIJOS = List.of(
            new Fijo(1, IMPUESTOS, "Mantenimiento de cuenta Galicia", 4200, DEBITO),
            new Fijo(3, SEGUROS, "FEDERA8 seguro de la moto", 28500, TARJETA),
            new Fijo(5, SERVICIOS, "Internet", 22900, DEBITO),
            new Fijo(7, SUSCRIPCIONES, "Netflix", 11999, TARJETA),
            new Fijo(10, SERVICIOS, "Luz", 27800, DEBITO),
            new Fijo(12, SUSCRIPCIONES, "Spotify", 4999, TARJETA),
            new Fijo(15, SERVICIOS, "Gas", 13600, TRANSFERENCIA),
            new Fijo(18, SUSCRIPCIONES, "Google One", 2400, TARJETA),
            new Fijo(20, SERVICIOS, "Agua", 9100, DEBITO),
            new Fijo(25, IMPUESTOS, "Impuesto de sellos", 1650, TARJETA));

    /** Lo que se compra en el kiosco: casi siempre por debajo del umbral hormiga ($3.000). */
    private static final List<String> KIOSCO_CHICO = List.of("Alfajor", "Gaseosa", "Agua", "Caramelos", "Chicles",
            "Turrón", "Chocolate", "Galletitas", "Bebida energizante", "Pastillas");

    /** Una forma de gastar en una categoría variable: descripción, rango de montos y medios posibles. */
    private record Molde(String categoria, String descripcion, int minimo, int maximo, String... medios) {
    }

    private static final List<Molde> SUPER = List.of(
            new Molde(SUPERMERCADO, "Día", 6000, 32000, DEBITO, TARJETA, QR),
            new Molde(SUPERMERCADO, "Coto", 12000, 48000, TARJETA, DEBITO),
            new Molde(SUPERMERCADO, "Carrefour", 10000, 45000, TARJETA, DEBITO),
            new Molde(SUPERMERCADO, "Verdulería", 3500, 11000, EFECTIVO, QR),
            new Molde(SUPERMERCADO, "Carnicería", 9000, 26000, EFECTIVO, DEBITO, QR),
            new Molde(SUPERMERCADO, "Chino de la esquina", 3000, 14000, EFECTIVO, QR));
    private static final List<Molde> COMIDAS = List.of(
            new Molde(COMIDA, "Pizza por delivery", 11000, 19000, QR, TARJETA),
            new Molde(COMIDA, "Hamburguesas", 9000, 18000, TARJETA, QR),
            new Molde(COMIDA, "Empanadas", 7000, 14000, EFECTIVO, QR),
            new Molde(COMIDA, "Sushi", 16000, 28000, TARJETA),
            new Molde(COMIDA, "Rotisería", 6500, 12000, EFECTIVO, DEBITO),
            new Molde(COMIDA, "Café", 1800, 2900, QR, EFECTIVO),
            new Molde(COMIDA, "Medialunas", 1500, 2800, EFECTIVO));
    private static final List<Molde> SALIDA = List.of(
            new Molde(SALIDAS, "Cervezas con amigos", 9000, 26000, QR, TARJETA, EFECTIVO),
            new Molde(SALIDAS, "Cine", 8000, 15000, TARJETA, QR),
            new Molde(SALIDAS, "Bar", 10000, 30000, TARJETA, QR),
            new Molde(SALIDAS, "Boliche", 15000, 35000, EFECTIVO, TARJETA));
    private static final List<Molde> TRANSPORTES = List.of(
            new Molde(TRANSPORTE, "Nafta", 9000, 18000, TARJETA, DEBITO),
            new Molde(TRANSPORTE, "Estacionamiento", 1500, 4000, EFECTIVO, QR),
            new Molde(TRANSPORTE, "Lavado de la moto", 5000, 8000, EFECTIVO));
    private static final List<Molde> CASAS = List.of(
            new Molde(CASA, "Lavandina y detergente", 3500, 8000, DEBITO, QR),
            new Molde(CASA, "Bolsas de residuos", 2000, 4500, EFECTIVO, QR),
            new Molde(CASA, "Lamparitas", 4000, 12000, TARJETA, DEBITO),
            new Molde(CASA, "Ferretería", 5000, 18000, EFECTIVO, TARJETA));
    private static final List<Molde> SALUDES = List.of(
            new Molde(SALUD, "Farmacia", 4000, 22000, TARJETA, DEBITO, QR),
            new Molde(SALUD, "Ibuprofeno", 3000, 6500, EFECTIVO, QR));
    private static final List<Molde> ROPAS = List.of(
            new Molde(ROPA, "Remera", 15000, 28000, TARJETA, DEBITO),
            new Molde(ROPA, "Zapatillas", 65000, 120000, TARJETA),
            new Molde(ROPA, "Jean", 35000, 60000, TARJETA),
            new Molde(ROPA, "Campera", 70000, 140000, TARJETA));
    private static final List<Molde> OTRAS = List.of(
            new Molde(OTROS, "Peluquería", 9000, 15000, EFECTIVO, TRANSFERENCIA),
            new Molde(OTROS, "Regalo de cumpleaños", 15000, 35000, TARJETA, TRANSFERENCIA),
            new Molde(OTROS, "Fotocopias", 800, 2500, EFECTIVO));

    /** Productos del Día con su código de barras (del ticket real de ejemplo) o sin él. */
    private record Producto(String descripcion, String codigoBarras, int precio) {
    }

    private static final List<Producto> PRODUCTOS_DIA = List.of(
            new Producto("Galletitas cracker queso", "7792180145444", 2250),
            new Producto("Premezcla brownies", "7790070432858", 5460),
            new Producto("Papel higiénico simple hoja aloe", "7790250016182", 2760),
            new Producto("Minitostaditas jamón", "7790310983256", 3490),
            new Producto("Pasta mac & cheese", "7794000009547", 4488),
            new Producto("Seven Up lata 354 ml", "7791813777021", 2250),
            new Producto("Pasta dental Luminous White", "7509546700434", 5280),
            new Producto("Café en saquitos intenso", "7792360094968", 4850),
            new Producto("Cepillo dental Super Flex", "7509546075242", 2940),
            new Producto("Pan para hamburguesa", "8480017056221", 2090),
            new Producto("Leche entera 1 l", null, 1690),
            new Producto("Fideos tirabuzón 500 g", null, 1350),
            new Producto("Yerba mate 1 kg", null, 5900),
            new Producto("Arroz largo fino 1 kg", null, 1890),
            new Producto("Aceite de girasol 1,5 l", null, 3990),
            new Producto("Huevos x 12", null, 4200),
            new Producto("Queso cremoso x kg", null, 9800),
            new Producto("Yogur bebible frutilla", null, 1750));

    private final Random azar;
    private final YearMonth mesCaro;

    private PlanDatosPrueba(long semilla, YearMonth mesCaro) {
        this.azar = new Random(semilla);
        this.mesCaro = mesCaro;
    }

    /**
     * Del 1 del mes de hace {@code meses - 1} meses hasta {@code ahora}. El mes caro es el del medio (o el
     * primero, si son menos de 3).
     */
    public static Plan armar(LocalDateTime ahora, int meses, long semilla) {
        LocalDate hasta = ahora.toLocalDate();
        YearMonth primero = YearMonth.from(hasta).minusMonths(Math.max(meses, 1) - 1L);
        YearMonth caro = meses >= 3 ? primero.plusMonths(1) : primero;
        return new PlanDatosPrueba(semilla, caro).armar(primero.atDay(1), ahora);
    }

    private Plan armar(LocalDate desde, LocalDateTime ahora) {
        LocalDate hasta = ahora.toLocalDate();
        List<Gasto> gastos = new ArrayList<>();
        for (LocalDate dia = desde; !dia.isAfter(hasta); dia = dia.plusDays(1)) {
            gastos.addAll(delDia(dia));
        }
        List<Ingreso> ingresos = ingresos(desde, hasta);
        // Nada en el futuro: hoy, solo lo que ya pasó.
        gastos.removeIf(g -> g.fechaHora().isAfter(ahora));
        ingresos.removeIf(i -> i.fecha().isAfter(hasta));
        gastos.sort(Comparator.comparing(Gasto::fechaHora));
        return new Plan(desde, hasta, mesCaro, List.copyOf(gastos), List.copyOf(ingresos));
    }

    // --- Un día ---

    private List<Gasto> delDia(LocalDate dia) {
        boolean caro = YearMonth.from(dia).equals(mesCaro);
        boolean finDeSemana = dia.getDayOfWeek() == DayOfWeek.FRIDAY || dia.getDayOfWeek() == DayOfWeek.SATURDAY
                || dia.getDayOfWeek() == DayOfWeek.SUNDAY;
        List<Gasto> gastos = new ArrayList<>();

        for (Fijo f : FIJOS) {
            if (f.dia() == dia.getDayOfMonth()) {
                gastos.add(gasto(dia, LocalTime.of(9, 0).plusMinutes(azar.nextInt(120)), f.categoria(),
                        f.descripcion(), new BigDecimal(f.monto()), f.medio(), Origen.TEXTO, null));
            }
        }
        // Hormiga: el kiosco casi todos los días, a veces dos veces.
        if (azar.nextDouble() < 0.9) {
            gastos.add(kiosco(dia));
            if (azar.nextDouble() < 0.25) {
                gastos.add(kiosco(dia));
            }
        }

        int objetivo = MINIMO_POR_DIA + azar.nextInt(MAXIMO_POR_DIA - MINIMO_POR_DIA + 1);
        while (gastos.size() < objetivo) {
            gastos.add(variable(dia, caro, finDeSemana));
        }
        while (gastos.size() > MAXIMO_POR_DIA) {
            gastos.remove(gastos.size() - 1); // nunca pasa con los fijos de hoy, pero el tope es el tope
        }
        return gastos;
    }

    private Gasto kiosco(LocalDate dia) {
        String que = KIOSCO_CHICO.get(azar.nextInt(KIOSCO_CHICO.size()));
        // Uno de cada diez es una compra más grande, que ya no cuenta como hormiga.
        int monto = azar.nextDouble() < 0.1 ? entre(3200, 5500) : entre(700, 2900);
        String medio = azar.nextDouble() < 0.55 ? EFECTIVO : azar.nextDouble() < 0.8 ? QR : DEBITO;
        return gasto(dia, hora(8, 23), KIOSCO, que, redondear(monto), medio, origen(), null);
    }

    private Gasto variable(LocalDate dia, boolean caro, boolean finDeSemana) {
        double ropa = caro ? 4 : 1.5;
        double salidas = finDeSemana ? (caro ? 30 : 18) : (caro ? 6 : 3);
        double[] pesos = {22, 18, salidas, 12, 6, 5, ropa, 4};
        List<List<Molde>> grupos = List.of(SUPER, COMIDAS, SALIDA, TRANSPORTES, CASAS, SALUDES, ROPAS, OTRAS);
        List<Molde> grupo = grupos.get(elegir(pesos));
        Molde molde = grupo.get(azar.nextInt(grupo.size()));
        BigDecimal monto = redondear(entre(molde.minimo(), molde.maximo()));
        if (caro && molde.maximo() > 3000) {
            monto = redondear(monto.multiply(RECARGO_MES_CARO).intValue());
        }
        String medio = molde.medios()[azar.nextInt(molde.medios().length)];
        if (molde.descripcion().equals("Día") && azar.nextDouble() < 0.6) {
            Ticket ticket = ticketDia();
            return gasto(dia, hora(10, 21), SUPERMERCADO, "Día", ticket.subtotal().add(ticket.descuentos()), medio,
                    Origen.TICKET, ticket);
        }
        return gasto(dia, hora(9, 23), molde.categoria(), molde.descripcion(), monto, medio, origen(), null);
    }

    // --- Tickets del Día ---

    /** De 4 a 10 productos, algunos con el 20% de ClubDía debajo (como en el ticket real). */
    private Ticket ticketDia() {
        List<Producto> disponibles = new ArrayList<>(PRODUCTOS_DIA);
        int cuantos = 4 + azar.nextInt(7);
        List<ItemTicket> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal descuentos = BigDecimal.ZERO;
        for (int i = 0; i < cuantos && !disponibles.isEmpty(); i++) {
            Producto p = disponibles.remove(azar.nextInt(disponibles.size()));
            BigDecimal cantidad = BigDecimal.valueOf(azar.nextDouble() < 0.2 ? 2 : 1);
            BigDecimal precio = new BigDecimal(p.precio()).setScale(2);
            BigDecimal importe = precio.multiply(cantidad);
            items.add(new ItemTicket(p.descripcion(), null, p.codigoBarras(), cantidad, precio, importe));
            subtotal = subtotal.add(importe);
            if (azar.nextDouble() < 0.4) {
                BigDecimal descuento = importe.multiply(new BigDecimal("-0.20")).setScale(2, RoundingMode.HALF_UP);
                items.add(new ItemTicket("20% ClubDía", BigDecimal.ONE, descuento, descuento));
                descuentos = descuentos.add(descuento);
            }
        }
        return new Ticket("Día", "5601", subtotal, descuentos, List.copyOf(items));
    }

    // --- Ingresos ---

    private List<Ingreso> ingresos(LocalDate desde, LocalDate hasta) {
        List<Ingreso> ingresos = new ArrayList<>();
        int numero = 0;
        for (YearMonth mes = YearMonth.from(desde); !mes.isAfter(YearMonth.from(hasta)); mes = mes.plusMonths(1)) {
            // El sueldo, el primer día hábil, con un 3% de aumento por mes (paritarias).
            LocalDate dia = mes.atDay(1);
            while (dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
                dia = dia.plusDays(1);
            }
            BigDecimal aumento = BigDecimal.ONE.add(new BigDecimal("0.03").multiply(BigDecimal.valueOf(numero++)));
            ingresos.add(new Ingreso(ClaseIngreso.SUELDO, SUELDO.multiply(aumento).setScale(0, RoundingMode.HALF_UP),
                    null, GALICIA, dia));
        }
        YearMonth primero = YearMonth.from(desde);
        // Un aguinaldo (medio sueldo), cobrado en el primer mes.
        ingresos.add(new Ingreso(ClaseIngreso.AGUINALDO, SUELDO.divide(BigDecimal.TWO), null, GALICIA,
                primero.atDay(8)));
        ingresos.add(new Ingreso(ClaseIngreso.EXTRA, new BigDecimal("35000"), "Arreglo de una PC", MERCADO_PAGO,
                primero.atDay(17)));
        ingresos.add(new Ingreso(ClaseIngreso.EXTRA, new BigDecimal("120000"), "Venta de la bici", MERCADO_PAGO,
                primero.plusMonths(1).atDay(9)));
        ingresos.add(new Ingreso(ClaseIngreso.EXTRA, new BigDecimal("20000"), "Regalo de cumpleaños", null,
                primero.plusMonths(2).atDay(4)));
        return ingresos;
    }

    // --- Azar ---

    private Gasto gasto(LocalDate dia, LocalTime hora, String categoria, String descripcion, BigDecimal monto,
            String medio, Origen origen, Ticket ticket) {
        String cuenta = switch (medio) {
            case DEBITO -> GALICIA;
            case TRANSFERENCIA -> azar.nextBoolean() ? GALICIA : MERCADO_PAGO;
            case QR -> MERCADO_PAGO;
            default -> null;
        };
        return new Gasto(dia.atTime(hora), categoria, descripcion, monto.setScale(2), medio, cuenta, origen, ticket);
    }

    /** Casi todo por texto; algunos por audio. */
    private Origen origen() {
        return azar.nextDouble() < 0.15 ? Origen.AUDIO : Origen.TEXTO;
    }

    private LocalTime hora(int desde, int hasta) {
        return LocalTime.of(desde + azar.nextInt(hasta - desde), azar.nextInt(60));
    }

    private int entre(int minimo, int maximo) {
        return minimo + azar.nextInt(maximo - minimo + 1);
    }

    private int elegir(double[] pesos) {
        double total = 0;
        for (double p : pesos) {
            total += p;
        }
        double r = azar.nextDouble() * total;
        for (int i = 0; i < pesos.length; i++) {
            r -= pesos[i];
            if (r < 0) {
                return i;
            }
        }
        return pesos.length - 1;
    }

    /** Montos como los de la vida real: a la decena (y a la centena si son grandes). */
    private static BigDecimal redondear(int monto) {
        int paso = monto >= 10000 ? 100 : 10;
        return BigDecimal.valueOf(Math.round((double) monto / paso) * paso);
    }
}
