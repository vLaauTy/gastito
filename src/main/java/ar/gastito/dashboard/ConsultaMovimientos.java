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
import ar.gastito.ticket.ValidadorTicket;

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

    /** Un renglón del ticket. Un descuento viene con subtotal negativo. */
    public record ItemDelTicket(String descripcion, BigDecimal cantidad, BigDecimal precioUnitario,
            BigDecimal subtotal, boolean descuento) {
    }

    /**
     * Lo que se ve al desplegar una fila con ticket.
     *
     * @param sumaItems los productos menos los descuentos, o null si no se leyó ninguno
     * @param coincide  si la suma y el total difieren en hasta $10 (o no hay productos con qué comparar)
     * @param foto      si la foto está en disco y se puede pedir a /api/movimientos/{id}/foto
     */
    public record DetalleTicket(Long id, String comercio, List<ItemDelTicket> items, BigDecimal sumaItems,
            BigDecimal total, boolean coincide, boolean foto) {
    }

    /** @throws NoSuchElementException si no es un gasto guardado */
    @Transactional(readOnly = true)
    public DetalleTicket items(long id) {
        Movimiento m = consultas.gastoConItems(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el gasto " + id));
        List<ItemDelTicket> items = m.getItems().stream()
                .map(i -> new ItemDelTicket(i.getDescripcion(), i.getCantidad(), dosDecimales(i.getPrecioUnitario()),
                        dosDecimales(i.getSubtotal()), i.isDescuento()))
                .toList();
        BigDecimal suma = items.isEmpty() ? null
                : items.stream().map(ItemDelTicket::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = dosDecimales(m.getMonto());
        return new DetalleTicket(m.getId(), m.getComercio(), items, suma, total,
                suma == null || ValidadorTicket.coinciden(suma, total), fotos.archivo(m.getFotoTicket()).isPresent());
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
     */
    public record Fila(Long id, LocalDateTime fechaHora, String descripcion, Long categoriaId, String categoria,
            String emoji, String medio, boolean sinEspecificar, String origen, BigDecimal monto, boolean ticket,
            boolean foto) {
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

    private static Fila fila(Movimiento m) {
        return new Fila(m.getId(), m.getFechaHora(), m.getDescripcion(),
                m.getCategoria() == null ? null : m.getCategoria().getId(),
                m.getCategoria() == null ? null : m.getCategoria().getNombre(),
                m.getCategoria() == null ? null : m.getCategoria().getEmoji(),
                medio(m), sinEspecificar(m), m.getOrigen().name(),
                m.getMonto().setScale(2, RoundingMode.HALF_UP), m.getOrigen() == Origen.TICKET,
                m.getFotoTicket() != null);
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
