package ar.gastito.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.Origen;
import ar.gastito.movimiento.Periodo;
import ar.gastito.movimiento.TipoMovimiento;
import ar.gastito.ticket.FotosTicket;
import ar.gastito.ticket.ControlesTicket;

/** La tabla de movimientos del dashboard (gastos confirmados, con filtros y páginas) y el CSV del mes. */
@Service
public class ConsultaMovimientos {

    public static final int TAMANIO_MAXIMO = 100;

    /** Letras con tilde y su versión sin tilde, para buscar con TRANSLATE (existe en H2 y en PostgreSQL). */
    private static final String CON_TILDE = "ÁÉÍÓÚÜÑáéíóúüñ";
    private static final String SIN_TILDE = "AEIOUUNaeiouun";

    private static final DateTimeFormatter FECHA_CSV = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA_CSV = DateTimeFormatter.ofPattern("HH:mm");

    private final DashboardRepository consultas;
    private final FotosTicket fotos;

    public ConsultaMovimientos(DashboardRepository consultas, FotosTicket fotos) {
        this.consultas = consultas;
        this.fotos = fotos;
    }

    /**
     * Un renglón del ticket. Un descuento viene con subtotal negativo.
     *
     * @param descripcionOriginal como está impresa, o null
     * @param codigoBarras        EAN-8 o EAN-13, o null
     */
    public record ItemDelTicket(String descripcion, String descripcionOriginal, String codigoBarras,
            BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal subtotal, boolean descuento) {
    }

    /**
     * Un control de las cuentas del ticket que no cierra (ver {@link ControlesTicket}). Los descuentos van en
     * negativo; el texto lo arma la página según el control.
     */
    public record DiferenciaDelTicket(ControlesTicket.Control control, BigDecimal calculado, BigDecimal impreso) {
    }

    /**
     * Lo que se ve al desplegar una fila con ticket.
     *
     * @param sumaItems          los productos menos los descuentos, o null si no se leyó ninguno
     * @param subtotalImpreso    el SUBTOTAL del ticket, o null
     * @param descuentosImpresos el total de descuentos impreso (negativo), o null
     * @param coincide           si todas las cuentas cierran (hasta $10 de diferencia)
     * @param diferencias        las cuentas que no cierran
     * @param lineasIlegibles    las líneas que la IA no pudo leer
     * @param foto               si la foto está en disco y se puede pedir a /api/movimientos/{id}/foto
     */
    public record DetalleTicket(Long id, String comercio, String sucursal, List<ItemDelTicket> items,
            BigDecimal sumaItems, BigDecimal subtotalImpreso, BigDecimal descuentosImpresos, BigDecimal total,
            boolean coincide, List<DiferenciaDelTicket> diferencias, List<RenglonDudosoDelTicket> renglonesDudosos,
            List<String> lineasIlegibles, boolean foto) {
    }

    /** Un producto donde cantidad × precio unitario no da el importe (ver {@link ControlesTicket#renglonesDudosos}). */
    public record RenglonDudosoDelTicket(int renglon, String descripcion, BigDecimal cantidad,
            BigDecimal precioUnitario, BigDecimal calculado, BigDecimal impreso) {
    }

    /** @throws NoSuchElementException si no es un gasto guardado */
    @Transactional(readOnly = true)
    public DetalleTicket items(long id) {
        Movimiento m = consultas.gastoConItems(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el gasto " + id));
        List<ItemDelTicket> items = m.getItems().stream()
                .map(i -> new ItemDelTicket(i.getDescripcion(), i.getDescripcionOriginal(), i.getCodigoBarras(),
                        i.getCantidad(), dosDecimales(i.getPrecioUnitario()), dosDecimales(i.getSubtotal()),
                        i.isDescuento()))
                .toList();
        BigDecimal suma = items.isEmpty() ? null
                : items.stream().map(ItemDelTicket::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<DiferenciaDelTicket> diferencias = ControlesTicket.revisar(m).stream()
                .map(d -> new DiferenciaDelTicket(d.control(), dosDecimales(d.calculado()), dosDecimales(d.impreso())))
                .toList();
        // Los renglones dudosos y las líneas ilegibles solo importan si alguna cuenta no cierra.
        List<RenglonDudosoDelTicket> dudosos = diferencias.isEmpty() ? List.of()
                : ControlesTicket.renglonesDudosos(m).stream()
                        .map(r -> new RenglonDudosoDelTicket(r.renglon(), r.descripcion(), r.cantidad(),
                                dosDecimales(r.precioUnitario()), dosDecimales(r.calculado()), dosDecimales(r.impreso())))
                        .toList();
        return new DetalleTicket(m.getId(), m.getComercio(), m.getSucursal(), items, suma,
                dosDecimales(m.getSubtotalImpreso()), dosDecimales(m.getDescuentosImpresos()),
                dosDecimales(m.getMonto()), diferencias.isEmpty(), diferencias, dudosos,
                diferencias.isEmpty() ? List.of() : m.getLineasIlegibles(),
                fotos.archivo(m.getFotoTicket()).isPresent());
    }

    /** La foto del ticket de un gasto guardado, si está en disco. */
    @Transactional(readOnly = true)
    public Optional<Path> foto(long id) {
        return consultas.gastoConItems(id).flatMap(m -> fotos.archivo(m.getFotoTicket()));
    }

    private static BigDecimal dosDecimales(BigDecimal monto) {
        return monto == null ? null : monto.setScale(2, RoundingMode.HALF_UP);
    }

    /** Todos opcionales menos el mes. {@code q} busca en la descripción, sin distinguir tildes ni mayúsculas. */
    public record Filtros(YearMonth mes, Long categoriaId, Long medioPagoId, Origen origen, String q) {
    }

    /**
     * @param medio            para la columna Medio: "QR · Mercado Pago", "Efectivo", "Sin especificar"…
     * @param sinEspecificar   si no se sabe con qué se pagó (se muestra en gris, para corregirlo)
     * @param ticket           si vino de la foto de un ticket (la fila se despliega con los productos)
     * @param foto             si guardó la foto del ticket
     * @param estimado         el cobro de un fijo tildado con el monto aproximado, sin corregir
     * @param medioPagoId      para el diálogo de editar; null si no se sabe
     * @param cuentaId         para el diálogo de editar; null si no tiene
     * @param especial         lo que no se borra como un gasto común (ver {@link Especial}), o null
     * @param cuotaActual      de una cuota, cuál es; si no, null
     * @param cuotaTotal       de una cuota, de cuántas; si no, null
     */
    public record Fila(Long id, LocalDateTime fechaHora, String descripcion, Long categoriaId, String categoria,
            String emoji, String medio, boolean sinEspecificar, String origen, BigDecimal monto, boolean ticket,
            boolean foto, boolean estimado, Long medioPagoId, Long cuentaId, Especial especial, Integer cuotaActual,
            Integer cuotaTotal) {

        /** Una fila de un gasto común, sin los datos para editar (los tests y lo que no se edita). */
        public Fila(Long id, LocalDateTime fechaHora, String descripcion, Long categoriaId, String categoria,
                String emoji, String medio, boolean sinEspecificar, String origen, BigDecimal monto, boolean ticket,
                boolean foto, boolean estimado) {
            this(id, fechaHora, descripcion, categoriaId, categoria, emoji, medio, sinEspecificar, origen, monto,
                    ticket, foto, estimado, null, null, null, null, null);
        }
    }

    /**
     * Un gasto que no se borra como uno común: una juntada (se borra entera, o es una parte dada por perdida), una
     * cuota (se cancela el plan desde ella) o el pago de un fijo (el fijo vuelve a "Falta").
     */
    public enum Especial {
        JUNTADA, CUOTA, FIJO;

        static Especial de(Movimiento m) {
            return m.isJuntada() ? JUNTADA : m.getPlanCuotasId() != null ? CUOTA : m.getGastoFijoId() != null ? FIJO
                    : null;
        }
    }

    public record Pagina(List<Fila> elementos, int pagina, int tamanio, long totalElementos, int totalPaginas) {
    }

    @Transactional(readOnly = true)
    public Pagina buscar(Filtros filtros, int pagina, int tamanio) {
        if (pagina < 0 || tamanio < 1 || tamanio > TAMANIO_MAXIMO) {
            throw new IllegalArgumentException("Página o tamaño inválidos (el tamaño va de 1 a " + TAMANIO_MAXIMO + ")");
        }
        Page<Movimiento> resultado = consultas.findAll(especificacion(filtros),
                PageRequest.of(pagina, tamanio, Sort.by(Sort.Order.desc("fechaHora"), Sort.Order.desc("id"))));
        return new Pagina(resultado.getContent().stream().map(ConsultaMovimientos::fila).toList(), pagina, tamanio,
                resultado.getTotalElements(), resultado.getTotalPages());
    }

    /**
     * Gastos e ingresos confirmados del mes, para Excel en castellano: separador ";", UTF-8 con BOM, montos con
     * coma decimal y sin separador de miles. Las celdas que empiezan con =, +, - o @ van con un apóstrofo adelante
     * para que Excel no las tome como fórmulas.
     */
    @Transactional(readOnly = true)
    public byte[] csv(YearMonth mes) {
        Periodo periodo = Periodo.delMes(mes);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Fecha;Hora;Tipo;Clase;Descripción;Categoría;Medio;Origen;Monto\r\n");
        for (Movimiento m : consultas.gastosEIngresos(periodo.desde(), periodo.hasta())) {
            List<String> celdas = new ArrayList<>();
            celdas.add(FECHA_CSV.format(m.getFechaHora()));
            celdas.add(HORA_CSV.format(m.getFechaHora()));
            celdas.add(m.isIngreso() ? "Ingreso" : "Gasto");
            celdas.add(m.getClaseIngreso() == null ? "" : m.getClaseIngreso().getNombre());
            celdas.add(m.getDescripcion() == null ? "" : m.getDescripcion());
            celdas.add(m.getCategoria() == null ? "" : m.getCategoria().getNombre());
            celdas.add(medio(m));
            celdas.add(m.getOrigen().name());
            celdas.add(m.getMonto().setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
            sb.append(String.join(";", celdas.stream().map(ConsultaMovimientos::celda).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String celda(String valor) {
        String v = valor;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static Specification<Movimiento> especificacion(Filtros f) {
        Periodo periodo = Periodo.delMes(f.mes());
        return (root, query, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            condiciones.add(cb.equal(root.get("estado"), EstadoMovimiento.CONFIRMADO));
            condiciones.add(cb.equal(root.get("tipo"), TipoMovimiento.GASTO));
            condiciones.add(cb.greaterThanOrEqualTo(root.get("fechaHora"), periodo.desde()));
            condiciones.add(cb.lessThan(root.get("fechaHora"), periodo.hasta()));
            if (f.categoriaId() != null) {
                condiciones.add(cb.equal(root.get("categoria").get("id"), f.categoriaId()));
            }
            if (f.medioPagoId() != null) {
                condiciones.add(cb.equal(root.get("medioPago").get("id"), f.medioPagoId()));
            }
            if (f.origen() != null) {
                condiciones.add(cb.equal(root.get("origen"), f.origen()));
            }
            String q = normalizar(f.q());
            if (!q.isEmpty()) {
                Expression<String> descripcion = cb.lower(cb.function("translate", String.class,
                        root.get("descripcion"), cb.literal(CON_TILDE), cb.literal(SIN_TILDE)));
                condiciones.add(cb.like(descripcion, "%" + escaparLike(q) + "%", '\\'));
            }
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
    }

    /** "Café" → "cafe". */
    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
    }

    private static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Fila fila(Movimiento m) {
        return new Fila(m.getId(), m.getFechaHora(), m.getDescripcion(),
                m.getCategoria() == null ? null : m.getCategoria().getId(),
                m.getCategoria() == null ? null : m.getCategoria().getNombre(),
                m.getCategoria() == null ? null : m.getCategoria().getEmoji(),
                medio(m), sinEspecificar(m), m.getOrigen().name(),
                m.getMonto().setScale(2, RoundingMode.HALF_UP), m.getOrigen() == Origen.TICKET,
                m.getFotoTicket() != null, m.isMontoEstimado(),
                m.getMedioPago() == null ? null : m.getMedioPago().getId(),
                m.getCuenta() == null ? null : m.getCuenta().getId(),
                Especial.de(m), m.getCuotaActual(), m.getCuotaTotal());
    }

    private static boolean sinEspecificar(Movimiento m) {
        return m.getTarjeta() == null && m.getCuenta() == null
                && (m.getMedioPago() == null || !m.getMedioPago().isEfectivo());
    }

    /** "Tarjeta de crédito · MasterCard Gold", "QR · Mercado Pago", "Efectivo", "Débito" o "Sin especificar". */
    static String medio(Movimiento m) {
        String nombreMedio = m.getMedioPago() == null ? null : m.getMedioPago().getNombre();
        String billetera = m.getTarjeta() != null ? m.getTarjeta().getNombre()
                : m.getCuenta() != null ? m.getCuenta().getNombre() : null;
        if (nombreMedio != null && billetera != null) {
            return nombreMedio + " · " + billetera;
        }
        if (nombreMedio != null) {
            return nombreMedio;
        }
        return billetera != null ? billetera : "Sin especificar";
    }
}
