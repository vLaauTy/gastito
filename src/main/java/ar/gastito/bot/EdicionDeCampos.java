package ar.gastito.bot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ar.gastito.bot.Tarjetas.Campo;
import ar.gastito.interpretacion.Montos;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;

/**
 * La tarjeta de un gasto esperando que el usuario escriba (o diga) el valor de un campo: el monto que falta, o el
 * monto, la fecha o la descripción desde ✏️ Editar. Un texto se aplica directo; un audio se propone primero. Un gasto
 * que se guardó solo se edita igual, y su tarjeta vuelve a ser la de "✅ Guardado".
 */
class EdicionDeCampos {

    private static final Logger log = LoggerFactory.getLogger(EdicionDeCampos.class);

    static final int LARGO_MAXIMO_DESCRIPCION = 255;

    /**
     * @param mensajeId la tarjeta en el chat; null hasta que Telegram confirma el envío
     * @param propuesta el valor que se entendió de un audio, esperando que el usuario lo aplique; o null
     */
    record Espera(long movimientoId, Campo campo, Integer mensajeId, Valor propuesta) {

        Espera(long movimientoId, Campo campo, Integer mensajeId) {
            this(movimientoId, campo, mensajeId, null);
        }
    }

    /** Un valor ya leído para un campo de la tarjeta. */
    sealed interface Valor {
        record Monto(BigDecimal monto) implements Valor {
        }

        record Fecha(LocalDateTime fechaHora) implements Valor {
        }

        record Descripcion(String descripcion) implements Valor {
        }
    }

    private final MovimientoService movimientos;
    private final ConversacionJuntadas juntadas;
    private final Tarjetas tarjetas;
    private final FormatoMensajes formato;
    private final ResolvedorFechas fechas;
    private final Clock clock;
    private final GuardadoAutomatico guardado;

    /** Por chat: el campo de qué tarjeta espera que el usuario escriba (o diga) el valor nuevo. */
    private final Map<Long, Espera> esperas = new ConcurrentHashMap<>();

    EdicionDeCampos(MovimientoService movimientos, ConversacionJuntadas juntadas, Tarjetas tarjetas,
            FormatoMensajes formato, ResolvedorFechas fechas, Clock clock, GuardadoAutomatico guardado) {
        this.movimientos = movimientos;
        this.juntadas = juntadas;
        this.tarjetas = tarjetas;
        this.formato = formato;
        this.fechas = fechas;
        this.clock = clock;
        this.guardado = guardado;
    }

    /** Una tarjeta nueva sin monto: queda esperando que lo escriba (todavía no se sabe en qué mensaje está). */
    void esperarMonto(long chatId, long movimientoId) {
        esperas.put(chatId, new Espera(movimientoId, Campo.MONTO, null));
    }

    /** La espera del chat, si había una; deja de esperar. */
    Espera quitar(long chatId) {
        return esperas.remove(chatId);
    }

    /** Telegram confirmó el envío de la tarjeta de un gasto: si se espera un valor para ella, se anota dónde está. */
    void tarjetaEnviada(long chatId, long movimientoId, int mensajeId) {
        esperas.computeIfPresent(chatId, (chat, e) -> e.movimientoId() == movimientoId && e.mensajeId() == null
                ? new Espera(movimientoId, e.campo(), mensajeId, e.propuesta())
                : e);
    }

    /** Aplica el valor que escribió el usuario. Vacío si no sirve para ese campo. */
    Optional<List<Accion>> aplicar(long chatId, Espera espera, String texto, int mensajeUsuario) {
        Optional<Valor> valor = leer(espera.campo(), texto);
        if (valor.isEmpty()) {
            return Optional.empty();
        }
        Movimiento m;
        try {
            m = aplicar(espera.movimientoId(), valor.get());
        } catch (MovimientoNoPendienteException | NoSuchElementException e) {
            return Optional.empty();
        }
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.Borrar(mensajeUsuario));
        if (espera.mensajeId() != null) {
            acciones.add(principal(chatId, espera.mensajeId(), m));
        } else {
            acciones.add(Accion.Enviar.tarjeta(tarjetas.actual(m), teclado(m), m.getId()));
        }
        return Optional.of(acciones);
    }

    /** Muestra el valor que se escuchó para el campo en edición, sin aplicarlo. Vacío si no sirve para ese campo. */
    Optional<List<Accion>> proponer(long chatId, Espera espera, String escuchado, int mensajeUsuario) {
        Optional<Valor> valor = leer(espera.campo(), escuchado);
        if (valor.isEmpty()) {
            return Optional.empty();
        }
        Movimiento m;
        try {
            m = editable(espera.movimientoId());
        } catch (MovimientoNoPendienteException | NoSuchElementException e) {
            return Optional.empty();
        }
        esperas.put(chatId, new Espera(m.getId(), espera.campo(), espera.mensajeId(), valor.get()));
        String html = tarjetas.propuesta(m, escuchado, espera.campo(), formatear(valor.get()));
        Teclado teclado = tarjetas.propuesta(m.getId());
        return Optional.of(List.of(new Accion.Borrar(mensajeUsuario), espera.mensajeId() != null
                ? new Accion.Editar(espera.mensajeId(), html, teclado)
                : Accion.Enviar.tarjeta(html, teclado, m.getId())));
    }

    private String formatear(Valor valor) {
        return switch (valor) {
            case Valor.Monto v -> formato.pesos(v.monto());
            case Valor.Fecha v -> formato.fecha(v.fechaHora());
            case Valor.Descripcion v -> v.descripcion();
        };
    }

    /** Lee lo que escribió o dijo el usuario como valor del campo. Vacío si no sirve. */
    private Optional<Valor> leer(Campo campo, String texto) {
        return switch (campo) {
            case MONTO -> Montos.parsear(texto.replaceAll("(?i)\\s*pesos$", "")).map(Valor.Monto::new);
            case FECHA -> fechas.resolverTexto(texto, LocalDateTime.now(clock)).map(Valor.Fecha::new);
            case DESCRIPCION -> {
                if (texto.isEmpty()) {
                    yield Optional.empty();
                }
                String descripcion = texto.substring(0, 1).toUpperCase() + texto.substring(1);
                yield Optional.of(new Valor.Descripcion(descripcion.length() > LARGO_MAXIMO_DESCRIPCION
                        ? descripcion.substring(0, LARGO_MAXIMO_DESCRIPCION)
                        : descripcion));
            }
        };
    }

    private Movimiento aplicar(long movimientoId, Valor valor) {
        try {
            return switch (valor) {
                // En una juntada, el monto de la tarjeta es el total pagado: se reparte de nuevo.
                case Valor.Monto v -> juntadas.esJuntada(movimientoId)
                        ? juntadas.cambiarTotal(movimientoId, v.monto())
                        : movimientos.cambiarMonto(movimientoId, v.monto());
                case Valor.Fecha v -> movimientos.cambiarFechaHora(movimientoId, v.fechaHora());
                case Valor.Descripcion v -> movimientos.cambiarDescripcion(movimientoId, v.descripcion());
            };
        } catch (MovimientoNoPendienteException e) {
            Movimiento m = siGuardado(e);
            return switch (valor) {
                case Valor.Monto v -> editarGuardado(m, v.monto(), m.getDescripcion(), m.getFechaHora(),
                        m.getCategoria().getId(), idMedio(m));
                case Valor.Fecha v -> editarGuardado(m, m.getMonto(), m.getDescripcion(), v.fechaHora(),
                        m.getCategoria().getId(), idMedio(m));
                case Valor.Descripcion v -> editarGuardado(m, m.getMonto(), v.descripcion(), m.getFechaHora(),
                        m.getCategoria().getId(), idMedio(m));
            };
        }
    }

    /** 🏷 Categoría desde la tarjeta, pendiente o guardada sola. */
    Movimiento elegirCategoria(long id, long categoriaId) {
        try {
            return movimientos.elegirCategoria(id, categoriaId);
        } catch (MovimientoNoPendienteException e) {
            Movimiento m = siGuardado(e);
            return editarGuardado(m, m.getMonto(), m.getDescripcion(), m.getFechaHora(), categoriaId, idMedio(m));
        }
    }

    /** 💳 Medio de pago desde la tarjeta, pendiente o guardada sola. @param medioPagoId null es sin medio de pago */
    Movimiento cambiarMedioPago(long id, Long medioPagoId) {
        try {
            return movimientos.cambiarMedioPago(id, medioPagoId);
        } catch (MovimientoNoPendienteException e) {
            Movimiento m = siGuardado(e);
            return editarGuardado(m, m.getMonto(), m.getDescripcion(), m.getFechaHora(), m.getCategoria().getId(),
                    medioPagoId);
        }
    }

    /** El gasto ya no está pendiente: si se guardó, se edita igual; si se canceló, no. */
    private static Movimiento siGuardado(MovimientoNoPendienteException e) {
        if (e.getMovimiento().getEstado() != EstadoMovimiento.CONFIRMADO) {
            throw e;
        }
        return e.getMovimiento();
    }

    /** Un gasto guardado se edita entero: con lo que ya tenía y el dato nuevo. */
    private Movimiento editarGuardado(Movimiento m, BigDecimal monto, String descripcion, LocalDateTime fechaHora,
            Long categoriaId, Long medioPagoId) {
        return movimientos.editar(m.getId(), monto, descripcion, fechaHora, categoriaId, medioPagoId,
                m.getCuenta() == null ? null : m.getCuenta().getId());
    }

    private static Long idMedio(Movimiento m) {
        return m.getMedioPago() == null ? null : m.getMedioPago().getId();
    }

    /** Los botones de la tarjeta según cómo está: pendiente, o guardada sola. */
    private Teclado teclado(Movimiento m) {
        return m.getEstado() == EstadoMovimiento.CONFIRMADO ? guardado.teclado(m) : tarjetas.principal(m);
    }

    /** Vuelve la tarjeta que esperaba un valor a su estado normal. */
    List<Accion> restaurar(Espera espera) {
        if (espera.mensajeId() == null) {
            return List.of();
        }
        try {
            Movimiento m = movimientos.buscar(espera.movimientoId());
            if (m.getEstado() != EstadoMovimiento.CANCELADO) {
                return List.of(new Accion.Editar(espera.mensajeId(), tarjetas.actual(m), teclado(m)));
            }
        } catch (NoSuchElementException e) {
            log.debug("La tarjeta a restaurar ya no existe: {}", espera.movimientoId());
        }
        return List.of();
    }

    /** Aplica el valor que se entendió de un audio y la tarjeta vuelve a su estado normal. */
    List<Accion> aplicarPropuesta(long chatId, int mensajeId, long id, Espera espera) {
        if (espera == null || espera.propuesta() == null || espera.movimientoId() != id) {
            return List.of(principal(chatId, mensajeId, editable(id)),
                    new Accion.Avisar("Ese cambio ya no está pendiente"));
        }
        return List.of(principal(chatId, mensajeId, aplicar(id, espera.propuesta())), new Accion.Avisar("✅ Aplicado"));
    }

    /**
     * La tarjeta en su estado normal (la de "✅ Guardado" si se guardó sola). Si todavía falta el monto, queda
     * esperando que el usuario lo escriba.
     */
    Accion.Editar principal(long chatId, int mensajeId, Movimiento m) {
        if (m.getEstado() == EstadoMovimiento.CONFIRMADO) {
            return guardado.tarjeta(mensajeId, m);
        }
        if (m.getMonto() == null) {
            esperas.put(chatId, new Espera(m.getId(), Campo.MONTO, mensajeId));
            return new Accion.Editar(mensajeId, tarjetas.pidiendoMontoFaltante(m), tarjetas.principal(m));
        }
        return new Accion.Editar(mensajeId, tarjetas.pendiente(m), tarjetas.principal(m));
    }

    /** ✏️ Editar → un campo: la tarjeta lo pide y queda esperando el valor. */
    List<Accion> pedir(long chatId, int mensajeId, long id, Campo campo) {
        Movimiento m = editable(id);
        esperas.put(chatId, new Espera(id, campo, mensajeId));
        return List.of(new Accion.Editar(mensajeId, tarjetas.pidiendo(m, campo), tarjetas.soloVolver(id)));
    }

    Movimiento pendiente(long id) {
        Movimiento m = movimientos.buscar(id);
        if (!m.isPendiente()) {
            throw new MovimientoNoPendienteException(m);
        }
        return m;
    }

    /** Pendiente, o ya guardado (✏️ Editar sobre uno que se guardó solo). Uno cancelado ya no se edita. */
    Movimiento editable(long id) {
        Movimiento m = movimientos.buscar(id);
        if (m.getEstado() == EstadoMovimiento.CANCELADO) {
            throw new MovimientoNoPendienteException(m);
        }
        return m;
    }
}
