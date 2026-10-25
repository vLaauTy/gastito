package ar.gastito.movimiento;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compras repetidas: antes de mostrar la tarjeta de un gasto nuevo, se busca si ya hay uno muy parecido (ver
 * {@link ReglaRepetidos}) entre los gastos confirmados de alrededor y los cobros pendientes de gastos fijos del mes.
 * Si el usuario dice que es la misma compra, se unen: queda el que ya estaba, con lo mejor de los dos.
 */
@Service
public class RepetidosService {

    /** De dónde puede venir un gasto nuevo que se compara: lo generado por Gastito (fijos, cuotas) no. */
    private static final Set<Origen> COMPARABLES = Set.of(Origen.TEXTO, Origen.AUDIO, Origen.TICKET, Origen.AVISO);

    private final MovimientoRepository movimientos;
    private final MovimientoUnionRepository uniones;
    private final RepetidosProperties config;
    private final Clock clock;

    public RepetidosService(MovimientoRepository movimientos, MovimientoUnionRepository uniones,
            RepetidosProperties config, Clock clock) {
        this.movimientos = movimientos;
        this.uniones = uniones;
        this.config = config;
        this.clock = clock;
    }

    /** El gasto que ya estaba y se parece al nuevo, o vacío. Si hay varios, el de monto y hora más cercanos. */
    @Transactional(readOnly = true)
    public Optional<Movimiento> buscar(Movimiento nuevo) {
        if (nuevo.getTipo() != TipoMovimiento.GASTO || nuevo.getMonto() == null
                || !COMPARABLES.contains(nuevo.getOrigen())) {
            return Optional.empty();
        }
        Huella huella = huella(nuevo);
        Duration ventana = config.ventana();
        LocalDate periodo = nuevo.getFechaHora().toLocalDate().withDayOfMonth(1);
        return movimientos.huellasCandidatas(nuevo.getId(), nuevo.getFechaHora().minus(ventana),
                        nuevo.getFechaHora().plus(ventana), LocalDateTime.now(clock), periodo).stream()
                // El cobro de un fijo es del mes, no de una hora: con él no se mira la ventana.
                .filter(f -> ReglaRepetidos.parecidos(huella, huella(f), cobroDeFijo(f) ? null : ventana))
                .map(RepetidosService::huella)
                .min(Comparator.comparing((Huella c) -> c.monto().subtract(huella.monto()).abs())
                        .thenComparing(c -> Duration.between(c.fechaHora(), huella.fechaHora()).abs()))
                .flatMap(c -> movimientos.findConDetalleById(c.id()));
    }

    private static boolean cobroDeFijo(Object[] fila) {
        return ((Number) fila[5]).intValue() == 1;
    }

    /** La huella de un movimiento: en una juntada, el monto es el total pagado. */
    Huella huella(Movimiento m) {
        BigDecimal total = m.getId() == null ? null : movimientos.totalDeJuntada(m.getId());
        Long categoriaId = m.getCategoria() == null ? null : m.getCategoria().getId();
        return new Huella(m.getId(), m.getFechaHora(), total != null ? total : m.getMonto(), categoriaId,
                m.getComercio());
    }

    private static Huella huella(Object[] fila) {
        Object fecha = fila[1];
        LocalDateTime fechaHora = fecha instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) fecha;
        return new Huella(((Number) fila[0]).longValue(), fechaHora, numero(fila[2]),
                fila[3] == null ? null : ((Number) fila[3]).longValue(), (String) fila[4]);
    }

    private static BigDecimal numero(Object valor) {
        return valor instanceof BigDecimal b ? b : new BigDecimal(valor.toString());
    }

    /**
     * "Es la misma": el nuevo (pendiente) se une al que ya estaba, que queda con lo mejor de los dos. El nuevo se
     * borra; su texto, su origen y su uso de la IA quedan con el que queda. Si el que estaba era el cobro pendiente
     * de un fijo, se confirma con lo que se cargó.
     *
     * @return el que quedó y, si la foto del nuevo no pasó (los dos eran tickets), su ruta para borrarla
     */
    @Transactional
    public Union unir(Long nuevoId, Long existenteId) {
        Movimiento nuevo = movimientos.findConDetalleById(nuevoId).orElseThrow();
        Movimiento existente = movimientos.findConDetalleById(existenteId).orElseThrow();
        if (!nuevo.isPendiente()) {
            throw new MovimientoNoPendienteException(nuevo);
        }
        boolean cobroDeFijo = existente.isPendiente() && existente.getGastoFijoId() != null;
        if (existente.getEstado() != EstadoMovimiento.CONFIRMADO && !cobroDeFijo) {
            throw new IllegalStateException("El movimiento " + existenteId + " no se puede unir");
        }

        String fotoQueSobra = nuevo.getFotoTicket();
        existente.absorber(nuevo);
        if (fotoQueSobra != null && fotoQueSobra.equals(existente.getFotoTicket())) {
            fotoQueSobra = null;
        }
        uniones.save(new MovimientoUnion(existente, nuevo.getOrigen(), nuevo.getMonto(), nuevo.getFechaHora(),
                nuevo.getTextoOriginal(), LocalDateTime.now(clock)));
        movimientos.pasarUsoIa(nuevo.getId(), existente.getId());
        movimientos.delete(nuevo);
        if (cobroDeFijo) {
            existente.confirmar();
        }
        movimientos.flush();
        return new Union(existente, fotoQueSobra);
    }

    /** @param fotoQueSobra la foto del que se borró, si no pasó al que quedó; null si no hay */
    public record Union(Movimiento movimiento, String fotoQueSobra) {
    }

    /** Las uniones de un movimiento, de la más vieja a la más nueva. */
    @Transactional(readOnly = true)
    public List<MovimientoUnion> uniones(Long movimientoId) {
        return uniones.findByMovimientoId(movimientoId);
    }
}
