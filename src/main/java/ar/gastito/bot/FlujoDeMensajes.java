package ar.gastito.bot;

import static ar.gastito.bot.FormatoMensajes.html;
import static ar.gastito.bot.TextosBot.escuche;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import ar.gastito.consulta.ConsultaService;
import ar.gastito.fijo.GastoFijo;
import ar.gastito.fijo.GastoFijoService;
import ar.gastito.ia.IaNoDisponibleException;
import ar.gastito.ia.PropositoIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.interpretacion.DivisorDeGastos;
import ar.gastito.interpretacion.Intencion;
import ar.gastito.interpretacion.InterpretadorService;
import ar.gastito.interpretacion.ValidadorInterpretacion;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoService;
import ar.gastito.movimiento.Origen;

/**
 * Lo que diga un mensaje o un audio (un gasto, varios, un ingreso, un fijo, una pregunta, una corrección…),
 * interpretado y respondido. Si no está claro qué quiere, pregunta con botones entre las opciones.
 */
class FlujoDeMensajes {

    /**
     * Botones de un mensaje dudoso: "dg:<id>" (es un gasto), "dp:<id>" (es una pregunta) y "do:<id>:<intención>"
     * (cualquier otra de las opciones, por su ordinal).
     */
    static final String ES_GASTO = "dg";
    static final String ES_PREGUNTA = "dp";
    static final String ES_OTRA = "do";
    /** "Fue ≈ $32.000": el pago de un fijo dicho sin monto toma el aproximado del fijo. */
    static final String PAGO_APROXIMADO = "fy";
    /** Cuántos mensajes dudosos se recuerdan esperando un botón (los más viejos se olvidan). */
    static final int DUDOSOS_RECORDADOS = 20;

    /** Un mensaje que no está claro qué pide, esperando que el usuario elija entre las opciones. */
    private record Dudoso(InterpretadorService.Dudoso dudoso, String texto, Origen origen) {
    }

    private final AtomicLong siguienteDudoso = new AtomicLong();
    private final Map<Long, Dudoso> dudosos = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Dudoso> mas) {
            return size() > DUDOSOS_RECORDADOS;
        }
    });

    private final InterpretadorService interpretador;
    private final MovimientoService movimientos;
    private final RegistroUsoIa usoIa;
    private final Tarjetas tarjetas;
    private final FormatoMensajes formato;
    private final ComandosIngreso comandosIngreso;
    private final ConsultaService consultas;
    private final FormatoConsulta formatoConsulta;
    private final ConversacionRepetidos repetidos;
    private final ConversacionJuntadas juntadas;
    private final ConversacionFijos fijos;
    private final ConversacionAltaFijo altaFijo;
    private final PedidosNaturales naturales;
    private final GastoFijoService gastosFijos;
    private final ConversacionVarios varios;
    private final ConversacionCuotas cuotas;
    private final ComandosSueldo comandosSueldo;
    private final EdicionDeCampos edicion;
    private final ComandosBot comandos;

    FlujoDeMensajes(InterpretadorService interpretador, MovimientoService movimientos, RegistroUsoIa usoIa,
            Tarjetas tarjetas, FormatoMensajes formato, ComandosIngreso comandosIngreso, ConsultaService consultas,
            FormatoConsulta formatoConsulta, ConversacionRepetidos repetidos, ConversacionJuntadas juntadas,
            ConversacionFijos fijos, ConversacionAltaFijo altaFijo, PedidosNaturales naturales,
            GastoFijoService gastosFijos, ConversacionVarios varios, ConversacionCuotas cuotas,
            ComandosSueldo comandosSueldo, EdicionDeCampos edicion, ComandosBot comandos) {
        this.interpretador = interpretador;
        this.movimientos = movimientos;
        this.usoIa = usoIa;
        this.tarjetas = tarjetas;
        this.formato = formato;
        this.comandosIngreso = comandosIngreso;
        this.consultas = consultas;
        this.formatoConsulta = formatoConsulta;
        this.repetidos = repetidos;
        this.juntadas = juntadas;
        this.fijos = fijos;
        this.altaFijo = altaFijo;
        this.naturales = naturales;
        this.gastosFijos = gastosFijos;
        this.varios = varios;
        this.cuotas = cuotas;
        this.comandosSueldo = comandosSueldo;
        this.edicion = edicion;
        this.comandos = comandos;
    }

    /** Lo que diga el mensaje (un gasto, un ingreso, un fijo, una pregunta, una corrección…), interpretado. */
    List<Accion> nuevoGasto(long chatId, String texto, Origen origen) {
        boolean audio = origen == Origen.AUDIO;
        List<String> partes = DivisorDeGastos.separar(texto);
        if (partes.size() > 1) {
            Optional<Accion> varios = intentarVarios(partes, origen, texto, audio);
            if (varios.isPresent()) {
                return List.of(varios.get());
            }
        }
        InterpretadorService.Resultado resultado;
        try {
            resultado = interpretador.interpretar(texto, audio ? PropositoIa.AUDIO : PropositoIa.TEXTO);
        } catch (IaNoDisponibleException e) {
            return List.of(Accion.Enviar.texto((audio ? escuche(texto) : "") + """
                    No pude usar la IA para entender el mensaje: %s.
                    Lo simple lo entiendo igual (kiosco 1500); lo demás, probá de nuevo en un rato."""
                    .formatted(html(e.getMessage()))));
        }
        return responder(chatId, resultado, texto, origen);
    }

    private List<Accion> responder(long chatId, InterpretadorService.Resultado resultado, String texto,
            Origen origen) {
        boolean audio = origen == Origen.AUDIO;
        String prefijo = audio ? escuche(texto) : "";
        return switch (resultado) {
            case InterpretadorService.Interpretado i -> List.of(gastoInterpretado(chatId, i, texto, origen));
            case InterpretadorService.Pregunta _ -> List.of(Accion.Enviar.conMenu(responderPregunta(texto, audio)));
            case InterpretadorService.Dudoso d -> List.of(preguntarQueQuiere(d, texto, origen, prefijo));
            case InterpretadorService.Devolucion d -> List.of(juntadas.devolucion(d.datos(), origen, texto, prefijo));
            case InterpretadorService.AltaFijo a -> List.of(altaFijo.nueva(chatId, a.pedido(), prefijo));
            case InterpretadorService.Orden o -> orden(chatId, o.orden(), texto, origen, prefijo);
            case InterpretadorService.NoEsGasto _ -> List.of(Accion.Enviar.texto(prefijo + """
                    🤔 No encontré nada para hacer en %s.
                    Contame un gasto o un ingreso, preguntame algo o pedime una lista, como te salga. En /ayuda tenés ejemplos."""
                    .formatted(audio ? "lo que escuché" : "el mensaje")));
        };
    }

    private Accion gastoInterpretado(long chatId, InterpretadorService.Interpretado i, String texto, Origen origen) {
        Movimiento m = crearGasto(i, origen, texto);
        Optional<GastoFijo> fijo = m.isPendiente() ? gastosFijos.vincular(texto) : Optional.empty();
        fijo.ifPresent(f -> gastosFijos.vincular(m, f));
        if (m.getMonto() == null) {
            edicion.esperarMonto(chatId, m.getId());
            // "Ya pagué el seguro": si el fijo tiene un monto de referencia, un botón lo usa.
            Teclado teclado = fijo.flatMap(f -> fijos.aproximado(f.getId()))
                    .map(a -> conFila(tarjetas.principal(m), new Teclado.Boton("✅ Fue ≈ " + formato.pesos(a),
                            PAGO_APROXIMADO + ":" + m.getId())))
                    .orElseGet(() -> tarjetas.principal(m));
            return Accion.Enviar.tarjeta(tarjetas.pidiendoMontoFaltante(m), teclado, m.getId());
        }
        return tarjetaNueva(m);
    }

    private static Teclado conFila(Teclado teclado, Teclado.Boton boton) {
        List<List<Teclado.Boton>> filas = new ArrayList<>();
        filas.add(List.of(boton));
        filas.addAll(teclado.filas());
        return new Teclado(filas);
    }

    /** No está claro qué quiere: un botón por opción (2 o 3), la más probable primero. */
    private Accion preguntarQueQuiere(InterpretadorService.Dudoso d, String texto, Origen origen, String prefijo) {
        long id = siguienteDudoso.incrementAndGet();
        dudosos.put(id, new Dudoso(d, texto, origen));
        boolean gastoOPregunta = d.opciones().equals(List.of(Intencion.GASTO, Intencion.PREGUNTA));
        List<Teclado.Boton> botones = d.opciones().stream().map(o -> botonDeOpcion(o, id)).toList();
        return Accion.Enviar.conBotones(prefijo + (gastoOPregunta
                ? "🤔 ¿Es un gasto para cargar o una pregunta sobre tus gastos?" : "🤔 ¿Qué querés hacer?"),
                Teclado.constructor().grilla(botones, botones.size() <= 2 ? 2 : 1).build());
    }

    static Teclado.Boton botonDeOpcion(Intencion opcion, long id) {
        return switch (opcion) {
            case GASTO -> new Teclado.Boton("🧾 Es un gasto", ES_GASTO + ":" + id);
            case PREGUNTA -> new Teclado.Boton("❓ Es una pregunta", ES_PREGUNTA + ":" + id);
            default -> new Teclado.Boton(switch (opcion) {
                case INGRESO -> "💰 Es un ingreso";
                case DEVOLUCION -> "🤝 Me devolvieron plata";
                case ALTA_FIJO -> "📌 Agregar un gasto fijo";
                case BAJA_FIJO -> "🗑 Dar de baja un fijo";
                case CORREGIR -> "✏️ Corregir lo último";
                case BORRAR -> "🗑 Borrar lo último";
                case CALCULO_SUELDO -> "🧮 Calcular el sueldo";
                case CANCELAR_CUOTAS -> "🗂 Cancelar unas cuotas";
                case PERDONAR -> "🚫 Dar por perdida una deuda";
                default -> "📋 Ver una lista";
            }, ES_OTRA + ":" + id + ":" + opcion.ordinal());
        };
    }

    /** Los demás pedidos: un ingreso, corregir o borrar lo último, una lista, el sueldo, cuotas, deudas, fijos. */
    private List<Accion> orden(long chatId, ValidadorInterpretacion.Resultado orden, String texto, Origen origen,
            String prefijo) {
        return switch (orden) {
            case ValidadorInterpretacion.Ver v -> comandos.ver(chatId, v.lista());
            case ValidadorInterpretacion.Ingreso i -> {
                Movimiento m = comandosIngreso.crear(i, origen, texto);
                if (m.getMonto() == null) {
                    edicion.esperarMonto(chatId, m.getId());
                    yield List.of(Accion.Enviar.tarjeta(prefijo + tarjetas.pidiendoMontoFaltante(m),
                            tarjetas.principal(m), m.getId()));
                }
                yield List.of(Accion.Enviar.tarjeta(prefijo + tarjetas.pendiente(m), tarjetas.principal(m),
                        m.getId()));
            }
            case ValidadorInterpretacion.Correccion c -> List.of(naturales.corregir(c, prefijo));
            case ValidadorInterpretacion.Borrar _ -> List.of(naturales.borrar(prefijo));
            case ValidadorInterpretacion.CalculoSueldo c -> List.of(comandosSueldo.calcularDeHoras(c.horas(), prefijo));
            case ValidadorInterpretacion.CancelarCuotas c -> naturales.cancelarCuotas(c.cual(), prefijo)
                    .<List<Accion>>map(List::of).orElseGet(cuotas::lista);
            case ValidadorInterpretacion.Perdonar p -> naturales.perdonar(p.persona(), p.de(), prefijo)
                    .<List<Accion>>map(List::of).orElseGet(juntadas::abiertas);
            case ValidadorInterpretacion.BajaFijo b -> naturales.bajaDeFijo(b.cual(), prefijo)
                    .<List<Accion>>map(List::of).orElseGet(fijos::lista);
            default -> List.of(Accion.Enviar.texto(prefijo + "🤔 No entendí qué querés hacer. ¿Me lo contás de otra forma?"));
        };
    }

    /**
     * Varios gastos separados por {@link DivisorDeGastos}: se interpreta cada parte por separado (sin guardar
     * nada) y, solo si todas resultan un gasto con monto, se crean los pendientes y se vinculan a su fijo si
     * corresponde. Si alguna parte no es un gasto claro, se cae al camino de un solo gasto con el texto completo.
     */
    private Optional<Accion> intentarVarios(List<String> partes, Origen origen, String textoCompleto,
            boolean audio) {
        List<InterpretadorService.Interpretado> interpretados = new ArrayList<>();
        for (String parte : partes) {
            InterpretadorService.Resultado r;
            try {
                r = interpretador.interpretar(parte, audio ? PropositoIa.AUDIO : PropositoIa.TEXTO);
            } catch (IaNoDisponibleException e) {
                return Optional.empty();
            }
            if (!(r instanceof InterpretadorService.Interpretado i) || i.movimiento().monto() == null) {
                return Optional.empty();
            }
            interpretados.add(i);
        }
        List<Movimiento> creados = new ArrayList<>();
        for (int idx = 0; idx < partes.size(); idx++) {
            Movimiento m = crearGasto(interpretados.get(idx), origen, partes.get(idx));
            Optional<GastoFijo> fijo = gastosFijos.vincular(partes.get(idx));
            if (fijo.isPresent() && m.isPendiente()) {
                gastosFijos.vincular(m, fijo.get());
            }
            creados.add(m);
        }
        return Optional.of(varios.tarjeta(creados, audio ? escuche(textoCompleto) : ""));
    }

    private Movimiento crearGasto(InterpretadorService.Interpretado i, Origen origen, String texto) {
        Movimiento m = movimientos.crearPendiente(i.movimiento(), origen, texto);
        if (i.usoIaId() != null) {
            usoIa.asociarMovimiento(i.usoIaId(), m.getId());
        }
        // Una juntada sin monto queda como gasto común: sin el total no hay nada que repartir.
        if (i.juntada() != null && m.getMonto() != null) {
            return juntadas.crear(m, i.juntada());
        }
        if (i.cuotas() != null && m.getMonto() != null) {
            return cuotas.crear(m, i.cuotas());
        }
        return m;
    }

    /** La tarjeta de un gasto recién creado con monto: la de siempre, o la de "¿ya lo tenías registrado?". */
    Accion.Enviar tarjetaNueva(Movimiento m) {
        return fijos.siActualizaUnEstimado(m)
                .or(() -> repetidos.siEsRepetido(m))
                .orElseGet(() -> Accion.Enviar.tarjeta(tarjetas.pendiente(m)
                        + (m.getGastoFijoId() == null ? "" : fijos.avisoYaCargado(m)), tarjetas.principal(m),
                        m.getId()));
    }

    // --- Preguntas ---

    /** Responde con tool use. Si la IA no está, lo dice; si inventó números, quedan los que calculó Java. */
    private String responderPregunta(String texto, boolean audio) {
        String prefijo = audio ? escuche(texto) : "";
        try {
            return prefijo + formatoConsulta.respuesta(consultas.responder(texto));
        } catch (IaNoDisponibleException e) {
            return prefijo + "No pude responder la pregunta: " + html(e.getMessage()) + ".";
        }
    }

    // --- Botones de los dudosos ---

    /** "Es un gasto": se crea la tarjeta con lo que ya se había interpretado. */
    List<Accion> esUnGasto(long chatId, int mensajeId, long id) {
        Dudoso dudoso = dudosos.remove(id);
        if (dudoso == null) {
            return List.of(new Accion.Avisar("Ese mensaje ya no está pendiente"));
        }
        Movimiento m = crearGasto(dudoso.dudoso().comoGasto(), dudoso.origen(), dudoso.texto());
        return List.of(m.getMonto() == null ? edicion.principal(chatId, mensajeId, m)
                : repetidos.siEsRepetido(m, mensajeId).orElseGet(() -> edicion.principal(chatId, mensajeId, m)));
    }

    /** "Es una pregunta": la respuesta reemplaza a los botones. */
    List<Accion> esUnaPregunta(int mensajeId, long id) {
        Dudoso dudoso = dudosos.remove(id);
        if (dudoso == null) {
            return List.of(new Accion.Avisar("Ese mensaje ya no está pendiente"));
        }
        return List.of(new Accion.Editar(mensajeId,
                responderPregunta(dudoso.texto(), dudoso.origen() == Origen.AUDIO), Teclado.NINGUNO));
    }

    /**
     * Eligió otra de las opciones (un ingreso, un fijo, corregir lo último…): se valida la misma respuesta de la IA
     * con esa intención, sin volver a llamarla, y el mensaje de la pregunta queda con lo que eligió.
     */
    List<Accion> esOtra(long chatId, int mensajeId, long id, long ordinal) {
        Dudoso dudoso = dudosos.remove(id);
        if (dudoso == null || ordinal < 0 || ordinal >= Intencion.values().length) {
            return List.of(new Accion.Avisar("Ese mensaje ya no está pendiente"));
        }
        Intencion elegida = Intencion.values()[(int) ordinal];
        List<Accion> acciones = new ArrayList<>();
        acciones.add(new Accion.Editar(mensajeId, "👉 " + html(botonDeOpcion(elegida, id).texto()),
                Teclado.NINGUNO));
        acciones.addAll(responder(chatId, interpretador.reinterpretar(dudoso.dudoso(), elegida, dudoso.texto()),
                dudoso.texto(), dudoso.origen()));
        return acciones;
    }

    /** "✅ Fue ≈ $32.000": el pago de un fijo dicho sin monto toma el monto de referencia del fijo. */
    List<Accion> pagoAproximado(long chatId, int mensajeId, long movimientoId) {
        Movimiento m = edicion.pendiente(movimientoId);
        Optional<BigDecimal> aproximado = m.getGastoFijoId() == null ? Optional.empty()
                : fijos.aproximado(m.getGastoFijoId());
        if (aproximado.isEmpty()) {
            return List.of(edicion.principal(chatId, mensajeId, m),
                    new Accion.Avisar("Ese fijo no tiene monto de referencia"));
        }
        return List.of(edicion.principal(chatId, mensajeId, movimientos.cambiarMonto(movimientoId,
                aproximado.get())));
    }
}
