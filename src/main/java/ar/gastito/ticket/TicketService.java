package ar.gastito.ticket;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import ar.gastito.catalogo.Catalogo;
import ar.gastito.catalogo.CatalogoService;
import ar.gastito.config.GastitoProperties;
import ar.gastito.ia.ClienteIa;
import ar.gastito.ia.RegistroUsoIa;
import ar.gastito.interpretacion.HistorialCategorias;
import ar.gastito.movimiento.Movimiento;
import ar.gastito.movimiento.MovimientoInterpretado;
import ar.gastito.movimiento.MovimientoService;

/**
 * De la foto al gasto pendiente: achica la imagen, la lee con la IA, valida en Java, guarda el pendiente con sus
 * productos y la foto en disco. Después sigue el mismo flujo de confirmación que un texto o un audio.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final ImagenTicket imagenes;
    private final LectorTicket lector;
    private final ValidadorTicket validador;
    private final HistorialCategorias historial;
    private final CatalogoService catalogos;
    private final MovimientoService movimientos;
    private final RegistroUsoIa usoIa;
    private final FotosTicket fotos;
    private final DataSize tamanioMaximo;
    private final Clock clock;

    public TicketService(ImagenTicket imagenes, LectorTicket lector, ValidadorTicket validador,
            HistorialCategorias historial, CatalogoService catalogos, MovimientoService movimientos,
            RegistroUsoIa usoIa, FotosTicket fotos, GastitoProperties properties, Clock clock) {
        this.imagenes = imagenes;
        this.lector = lector;
        this.validador = validador;
        this.historial = historial;
        this.catalogos = catalogos;
        this.movimientos = movimientos;
        this.usoIa = usoIa;
        this.fotos = fotos;
        this.tamanioMaximo = properties.ticket().tamanioMaximo();
        this.clock = clock;
    }

    public sealed interface Resultado {
    }

    /** El gasto pendiente, con sus productos y la foto. */
    public record Leido(Movimiento movimiento) implements Resultado {
    }

    public record NoEsTicket() implements Resultado {
    }

    public record Ilegible(ValidadorTicket.Motivo motivo) implements Resultado {
    }

    public DataSize tamanioMaximo() {
        return tamanioMaximo;
    }

    /** Para no descargar de Telegram una imagen que después no se va a usar. */
    public boolean superaElTamanio(long bytes) {
        return bytes > tamanioMaximo.toBytes();
    }

    /**
     * @param pista el texto que mandó el usuario con la foto, o null
     * @throws ImagenInvalidaException si es demasiado grande o no se puede abrir
     * @throws ar.gastito.ia.IaNoDisponibleException si no se pudo usar la IA
     */
    public Resultado leer(byte[] original, String pista) {
        if (superaElTamanio(original.length)) {
            throw new ImagenInvalidaException(ImagenInvalidaException.Motivo.DEMASIADO_GRANDE,
                    "La imagen pesa " + original.length + " bytes");
        }
        ImagenTicket.Imagen imagen = imagenes.preparar(original);
        Catalogo catalogo = catalogos.cargar();
        LocalDateTime ahora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        ClienteIa.Respuesta<LecturaTicketIa> respuesta = lector.leer(imagen.jpeg(), pista, catalogo, ahora);

        return switch (validador.validar(respuesta.valor(), catalogo, ahora)) {
            case ValidadorTicket.Valido v -> new Leido(guardar(v, imagen, pista, respuesta.usoIaId(), catalogo));
            case ValidadorTicket.NoEsTicket n -> new NoEsTicket();
            case ValidadorTicket.Ilegible i -> new Ilegible(i.motivo());
        };
    }

    private Movimiento guardar(ValidadorTicket.Valido v, ImagenTicket.Imagen imagen, String pista, Long usoIaId,
            Catalogo catalogo) {
        // Lo que el usuario ya decidió para ese comercio manda sobre lo que dijo la IA.
        MovimientoInterpretado interpretado = v.movimiento();
        Optional<HistorialCategorias.Aprendido> aprendido = historial.buscar(interpretado.descripcion(), catalogo);
        if (aprendido.isPresent()) {
            interpretado = interpretado.conCategoriaElegida(aprendido.get().categoria());
        }
        String texto = pista == null || pista.isBlank() ? null : pista.strip();
        Movimiento m = movimientos.crearPendienteDeTicket(interpretado, v.comercio(), v.items(), texto);
        if (usoIaId != null) {
            usoIa.asociarMovimiento(usoIaId, m.getId());
        }
        try {
            String ruta = fotos.guardar(m.getId(), m.getFechaHora().toLocalDate(), imagen.jpeg());
            movimientos.asociarFotoTicket(m.getId(), ruta);
        } catch (IOException e) {
            // Sin la foto el gasto sirve igual: solo no se va a poder ver desde el dashboard.
            log.warn("No se pudo guardar la foto del ticket {}: {}", m.getId(), e.getMessage());
        }
        return movimientos.buscar(m.getId());
    }

    /** Un ticket cancelado no se guarda: tampoco su foto, que tiene datos de la compra. */
    public void borrarFoto(Movimiento m) {
        if (m.getFotoTicket() == null) {
            return;
        }
        fotos.borrar(m.getFotoTicket());
        movimientos.quitarFotoTicket(m.getId());
    }
}
