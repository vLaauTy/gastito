package ar.gastito.bot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ar.gastito.audio.TranscripcionService;
import ar.gastito.bot.Tarjetas.Campo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.consulta.ConsultaService;
import ar.gastito.dashboard.DashboardProperties;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.ResolvedorFechas;
import ar.gastito.movimiento.EstadoMovimiento;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoIncompletoException;
import ar.gastito.movimiento.MovimientoNoPendienteException;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;
import ar.gastito.resumen.ResumenService;
import ar.gastito.ticket.TicketService;

/**
 * La conversación con el usuario, sin depender de la API de Telegram: recibe textos, audios, fotos y botones y
 * devuelve {@link Accion}es. Cada gasto es una sola tarjeta que se edita en el lugar. Acá se decide a qué flujo va
 * cada cosa; los flujos están en {@link FlujoDeMensajes}, {@link ComandosBot}, {@link EdicionDeCampos} y
 * {@link MediosRecibidos}.
 */
@Component
public class ProcesadorMensajes {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorMensajes.class);

    static final String BIENVENIDA = TextosBot.BIENVENIDA;
    static final String AYUDA = TextosBot.AYUDA;

    private final MovimientoService movimientos;
    private final CatalogoService catalogos;
    private final Tarjetas tarjetas;
    private final ComandosIngreso comandosIngreso;
    private final TicketService tickets;
    private final ConversacionRepetidos repetidos;
    private final ConversacionJuntadas juntadas;
    private final ConversacionFijos fijos;
    private final ConversacionAltaFijo altaFijo;
    private final PedidosNaturales naturales;
    private final ConversacionVarios varios;
    private final ConversacionCuotas cuotas;
    private final ConversacionRecibos recibos;
    private final ConversacionEscalas escalas;
    private final ComandosSueldo comandosSueldo;

    private final GuardadoAutomatico guardado;
    private final EdicionDeCampos edicion;
    private final ComandosBot comandos;
    private final FlujoDeMensajes flujo;
    private final MediosRecibidos medios;

    public ProcesadorMensajes(InterpretadorService interpretador, MovimientoService movimientos,
            CatalogoService catalogos, RegistroUsoIa usoIa, ResolvedorFechas fechas, Tarjetas tarjetas,
            FormatoMensajes formato, GraficoMes grafico, ComandosIngreso comandosIngreso,
            TranscripcionService transcripciones, TicketService tickets, ResumenService resumenes,
            FormatoResumen formatoResumen, ConsultaService consultas, FormatoConsulta formatoConsulta,
            ConversacionRepetidos repetidos, ConversacionJuntadas juntadas, ConversacionFijos fijos,
            ConversacionAltaFijo altaFijo, PedidosNaturales naturales, ComandoDisponible disponible,
            GastoFijoService gastosFijos,
            ConversacionVarios varios, ConversacionCuotas cuotas, ConversacionRecibos recibos,
            ConversacionEscalas escalas,
            ComandosSueldo comandosSueldo, FormatoCostos formatoCostos, GastitoProperties properties,
            DashboardProperties dashboard, BotProperties bot, Clock clock) {
        this.movimientos = movimientos;
        this.catalogos = catalogos;
        this.tarjetas = tarjetas;
        this.comandosIngreso = comandosIngreso;
        this.tickets = tickets;
        this.repetidos = repetidos;
        this.juntadas = juntadas;
        this.fijos = fijos;
        this.altaFijo = altaFijo;
        this.naturales = naturales;
        this.varios = varios;
        this.cuotas = cuotas;
        this.recibos = recibos;
        this.escalas = escalas;
        this.comandosSueldo = comandosSueldo;
        this.guardado = new GuardadoAutomatico(bot, movimientos, catalogos, tarjetas, tickets);
        this.edicion = new EdicionDeCampos(movimientos, juntadas, tarjetas, formato, fechas, clock, guardado);
        // Los atajos que no se entienden sin IA se interpretan como cualquier mensaje (flujo se asigna abajo).
        this.comandos = new ComandosBot(movimientos, formato, grafico, comandosIngreso, resumenes, formatoResumen,
                juntadas, fijos, altaFijo, cuotas, comandosSueldo, disponible, formatoCostos, dashboard.url(),
                this::interpretarTexto);
        this.flujo = new FlujoDeMensajes(interpretador, movimientos, usoIa, tarjetas, formato, comandosIngreso,
                consultas, formatoConsulta, repetidos, juntadas, fijos, altaFijo, naturales, gastosFijos, varios,
                cuotas, comandosSueldo, edicion, comandos, guardado);
        this.medios = new MediosRecibidos(transcripciones, tickets, recibos, escalas, altaFijo, comandosSueldo, edicion,
                flujo, properties.audio().duracionMaxima());
    }

    /** Un texto como cualquier mensaje, para los atajos que no se entienden sin IA. */
    private List<Accion> interpretarTexto(long chatId, String texto) {
        return flujo.nuevoGasto(chatId, texto, Origen.TEXTO);
    }

    // --- Mensajes de texto ---

    /**
     * @param mensajeId el mensaje del usuario (se borra si era el valor de un campo que se estaba editando)
     */
    public List<Accion> procesarTexto(long chatId, int mensajeId, String texto) {
        String limpio = texto == null ? "" : texto.trim();
        String comando = comando(limpio);
        List<Accion> acciones = new ArrayList<>();

        // El alta de un fijo esperaba un dato (el monto, el día, el nombre). Si no sirve, se procesa normal.
        if (altaFijo.esperando(chatId)) {
            if (comando == null) {
                Optional<Accion.Enviar> respuesta = altaFijo.texto(chatId, limpio);
                if (respuesta.isPresent()) {
                    return List.of(respuesta.get());
                }
            }
            altaFijo.olvidar(chatId);
        }

        // El cobro de un fijo esperaba el monto ("Cambió el monto").
        if (fijos.esperando(chatId)) {
            if (comando == null) {
                return fijos.texto(chatId, limpio);
            }
            fijos.olvidar(chatId);
        }

        // La juntada esperaba un nombre o la parte de alguien.
        if (juntadas.esperando(chatId)) {
            if (comando == null) {
                return juntadas.texto(chatId, limpio);
            }
            juntadas.olvidar(chatId);
        }

        EdicionDeCampos.Espera espera = edicion.quitar(chatId);
        if (espera != null) {
            if (comando == null) {
                Optional<List<Accion>> aplicado = edicion.aplicar(chatId, espera, limpio, mensajeId);
                if (aplicado.isPresent()) {
                    return aplicado.get();
                }
            }
            // No era un valor para el campo: la tarjeta vuelve a su estado y el mensaje se procesa normal.
            acciones.addAll(edicion.restaurar(espera));
        }

        if (comando != null) {
            acciones.addAll(comandos.ejecutar(chatId, comando, limpio));
        } else if (comandosSueldo.pareceCalculo(limpio)) {
            acciones.add(comandosSueldo.calcularDeTexto(limpio));
        } else {
            acciones.addAll(flujo.nuevoGasto(chatId, limpio, Origen.TEXTO));
        }
        return acciones;
    }

    /** Telegram confirmó el envío de la tarjeta de un gasto: si se espera un valor para ella, se anota dónde está. */
    public void tarjetaEnviada(long chatId, long movimientoId, int mensajeId) {
        edicion.tarjetaEnviada(chatId, movimientoId, mensajeId);
    }

    // --- Audios, fotos y PDF ---

    /** @param mensajeId el audio del usuario (se borra si era el valor de un campo) */
    public List<Accion> procesarAudio(long chatId, int mensajeId, AudioRecibido audio) {
        return medios.audio(chatId, mensajeId, audio);
    }

    /** @param mensajeId la foto del usuario */
    public List<Accion> procesarFoto(long chatId, int mensajeId, FotoRecibida foto) {
        return medios.foto(chatId, foto);
    }

    /** Un PDF es un recibo de sueldo: queda la tarjeta con el neto y el detalle, para guardar. */
    public List<Accion> procesarPdf(long chatId, int mensajeId, PdfRecibido pdf) {
        return medios.pdf(chatId, pdf);
    }

    /** Un Excel es la escala de sueldo: queda el resumen por mes, para guardar. */
    public List<Accion> procesarExcel(long chatId, int mensajeId, ExcelRecibido excel) {
        return medios.excel(chatId, excel);
    }

    /** Whisper suele cerrar con un punto ("Kiosco 1500."), que no es parte del dato. */
    static String limpiarTranscripcion(String texto) {
        return MediosRecibidos.limpiarTranscripcion(texto);
    }

    /** "/hoy@gastito_bot extra" → "/hoy"; un botón del menú fijo → su comando. Null si no es un comando. */
    static String comando(String texto) {
        return ComandosBot.comando(texto);
    }

    // --- Botones ---

    /** @param mensajeId la tarjeta donde está el botón: se edita en el lugar */
    public List<Accion> procesarBoton(long chatId, int mensajeId, String datos) {
        FormatoResumen.Anteriores anteriores = FormatoResumen.leerAnteriores(datos);
        if (anteriores != null) {
            return comandos.verAnteriores(mensajeId, anteriores);
        }
        if (ConversacionEscalas.esBoton(datos)) {
            return escalas.boton(mensajeId, datos);
        }
        if (ComandosSueldo.esBoton(datos)) {
            return comandosSueldo.boton(mensajeId, datos);
        }
        String[] partes = datos == null ? new String[0] : datos.split(":");
        Long id = partes.length >= 2 ? numero(partes[1]) : null;
        Long elegido = partes.length >= 3 ? numero(partes[2]) : null;
        if (id == null || (partes.length >= 3 && elegido == null)) {
            return List.of(new Accion.Avisar("Botón inválido"));
        }

        List<Accion> acciones = new ArrayList<>();
        juntadas.olvidar(chatId);
        fijos.olvidar(chatId);
        altaFijo.olvidar(chatId);
        EdicionDeCampos.Espera previa = edicion.quitar(chatId);
        if (previa != null && (previa.mensajeId() == null || previa.mensajeId() != mensajeId)) {
            acciones.addAll(edicion.restaurar(previa));
        }
        try {
            acciones.addAll(Tarjetas.APLICAR.equals(partes[0])
                    ? edicion.aplicarPropuesta(chatId, mensajeId, id, previa)
                    : boton(chatId, mensajeId, partes[0], id, elegido));
        } catch (MovimientoNoPendienteException e) {
            Movimiento m = e.getMovimiento();
            acciones.add(new Accion.Editar(mensajeId, tarjetas.terminado(m), Teclado.NINGUNO));
            acciones.add(new Accion.Avisar(m.getEstado() == EstadoMovimiento.CONFIRMADO
                    ? "Ese gasto ya estaba guardado"
                    : "Ese gasto ya estaba cancelado"));
        } catch (MovimientoIncompletoException e) {
            acciones.add(edicion.principal(chatId, mensajeId, movimientos.buscar(id)));
            acciones.add(new Accion.Avisar("Antes de guardar falta elegir la categoría o poner el monto"));
        } catch (NoSuchElementException e) {
            log.warn("Botón para algo que no existe: {}", datos);
            acciones.add(new Accion.Avisar("No encontré ese gasto"));
        }
        return acciones;
    }

    private List<Accion> boton(long chatId, int mensajeId, String accion, long id, Long elegido) {
        return switch (accion) {
            case Tarjetas.GUARDAR -> {
                MovimientoService.Confirmacion c = movimientos.confirmar(id);
                if (c.movimiento().isJuntada()) {
                    yield juntadas.guardada(mensajeId, c);
                }
                if (c.movimiento().getPlanCuotasId() != null) {
                    yield cuotas.guardada(mensajeId, c);
                }
                yield List.of(new Accion.Editar(mensajeId, tarjetas.guardado(c.movimiento(), c.totalMesCategoria()),
                        Teclado.NINGUNO), new Accion.Avisar("✅ Guardado"));
            }
            case Tarjetas.CANCELAR -> {
                Movimiento cancelado = movimientos.cancelar(id);
                if (cancelado.getOrigen() == Origen.TICKET) {
                    tickets.borrarFoto(cancelado);
                }
                yield List.of(new Accion.Editar(mensajeId, tarjetas.cancelado(cancelado), Teclado.NINGUNO),
                        new Accion.Avisar("Cancelado"));
            }
            case Tarjetas.VOLVER -> List.of(edicion.principal(chatId, mensajeId, edicion.editable(id)));
            case Tarjetas.EDITAR -> List.of(new Accion.Editar(mensajeId, tarjetas.actual(edicion.editable(id)),
                    tarjetas.edicion(id)));
            case Tarjetas.DESHACER -> guardado.deshacer(mensajeId, id);
            case Tarjetas.MEDIO_RAPIDO -> guardado.elegirMedio(mensajeId, id, requerido(elegido));
            case TarjetasRecibo.DESCARTAR -> recibos.descartar(mensajeId, id);
            case Tarjetas.PERIODO -> List.of(edicion.principal(chatId, mensajeId, comandosIngreso.alternarPeriodo(id)));
            case Tarjetas.OTRA_CATEGORIA -> List.of(new Accion.Editar(mensajeId,
                    tarjetas.actual(edicion.editable(id)), tarjetas.categorias(id, catalogos.cargar().categorias())));
            case Tarjetas.MEDIOS -> List.of(new Accion.Editar(mensajeId, tarjetas.actual(edicion.editable(id)),
                    tarjetas.medios(id, catalogos.cargar().mediosPago())));
            case Tarjetas.CATEGORIA -> List.of(edicion.principal(chatId, mensajeId,
                    edicion.elegirCategoria(id, requerido(elegido))));
            case Tarjetas.MEDIO -> {
                long medio = requerido(elegido);
                yield List.of(edicion.principal(chatId, mensajeId,
                        edicion.cambiarMedioPago(id, medio == Tarjetas.SIN_MEDIO ? null : medio)));
            }
            case Tarjetas.EDITAR_MONTO -> edicion.pedir(chatId, mensajeId, id, Campo.MONTO);
            case Tarjetas.EDITAR_FECHA -> edicion.pedir(chatId, mensajeId, id, Campo.FECHA);
            case Tarjetas.EDITAR_DESCRIPCION -> edicion.pedir(chatId, mensajeId, id, Campo.DESCRIPCION);
            case Tarjetas.JUNTADA_NOMBRES, Tarjetas.JUNTADA_PARTE, Tarjetas.JUNTADA_PERDIDO, Tarjetas.JUNTADA_MENSAJE,
                    Tarjetas.JUNTADA_VER, Tarjetas.DEVOLUCION_DE, Tarjetas.DEVOLUCION_QUEDA_DEBIENDO,
                    Tarjetas.DEVOLUCION_SALDADO -> juntadas.boton(chatId, mensajeId, accion, id, elegido);
            case ConversacionFijos.BAJA, ConversacionFijos.ESTE_MES_NO_CHECKLIST -> fijos.boton(mensajeId, accion, id);
            case ConversacionAltaFijo.GUARDAR, ConversacionAltaFijo.CANCELAR, ConversacionAltaFijo.CATEGORIA,
                    ConversacionAltaFijo.DIA, ConversacionAltaFijo.MEDIO, ConversacionAltaFijo.MONTO_NO_SE,
                    ConversacionAltaFijo.EDITAR, ConversacionAltaFijo.CAMBIAR, ConversacionAltaFijo.FRECUENCIA,
                    ConversacionAltaFijo.VOLVER, ConversacionAltaFijo.ACTUALIZAR_EXISTENTE, ConversacionAltaFijo.ES_OTRO ->
                    altaFijo.boton(chatId, mensajeId, accion, id, elegido);
            case PedidosNaturales.APLICAR_CORRECCION, PedidosNaturales.BORRAR, PedidosNaturales.BAJA_FIJO,
                    PedidosNaturales.NADA -> naturales.boton(mensajeId, accion, id);
            case FlujoDeMensajes.PAGO_APROXIMADO -> flujo.pagoAproximado(chatId, mensajeId, id);
            case FlujoDeMensajes.ES_OTRA -> flujo.esOtra(chatId, mensajeId, id, requerido(elegido));
            case ConversacionFijos.CONFIRMAR_COBRO, ConversacionFijos.CAMBIO_EL_MONTO, ConversacionFijos.ESTE_MES_NO ->
                    fijos.botonDeCobro(chatId, mensajeId, accion, id);
            case ConversacionFijos.PAGADO, ConversacionFijos.ACTUALIZAR_ESTIMADO ->
                    fijos.botonDePago(mensajeId, accion, id);
            case ConversacionFijos.OTRO_PAGO -> List.of(edicion.principal(chatId, mensajeId, edicion.pendiente(id)));
            case ConversacionCuotas.CANCELAR_PLAN -> cuotas.boton(mensajeId, id);
            case ConversacionVarios.GUARDAR_TODO, ConversacionVarios.CANCELAR_TODOS -> varios.boton(mensajeId,
                    accion, id);
            case Tarjetas.UNIR -> repetidos.unir(mensajeId, id, requerido(elegido));
            case Tarjetas.DE_NUEVO -> repetidos.deNuevo(mensajeId, id)
                    .orElseGet(() -> List.of(edicion.principal(chatId, mensajeId, edicion.pendiente(id))));
            case FlujoDeMensajes.ES_GASTO -> flujo.esUnGasto(chatId, mensajeId, id);
            case FlujoDeMensajes.ES_PREGUNTA -> flujo.esUnaPregunta(mensajeId, id);
            default -> List.of(new Accion.Avisar("Botón inválido"));
        };
    }

    private static long requerido(Long elegido) {
        if (elegido == null) {
            throw new NoSuchElementException("Falta el id elegido");
        }
        return elegido;
    }

    private static Long numero(String texto) {
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
