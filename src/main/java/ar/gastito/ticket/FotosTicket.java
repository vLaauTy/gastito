package ar.gastito.ticket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;

/**
 * Las fotos de los tickets en disco: carpetaDatos/tickets/AAAA/MM/<id>.jpg. En la base queda la ruta relativa a
 * la carpeta de datos ("tickets/2026/10/15.jpg"), así la carpeta se puede mover.
 */
@Component
public class FotosTicket {

    private static final Logger log = LoggerFactory.getLogger(FotosTicket.class);
    static final String CARPETA = "tickets";

    private final Path datos;
    private final Path tickets;

    @Autowired
    public FotosTicket(GastitoProperties properties) {
        this(Path.of(properties.ticket().carpetaDatos()));
    }

    FotosTicket(Path carpetaDatos) {
        this.datos = carpetaDatos.toAbsolutePath().normalize();
        this.tickets = datos.resolve(CARPETA);
    }

    /** @return la ruta relativa a la carpeta de datos, con barras "/" */
    public String guardar(long movimientoId, LocalDate fecha, byte[] jpeg) throws IOException {
        String ruta = "%s/%04d/%02d/%d.jpg".formatted(CARPETA, fecha.getYear(), fecha.getMonthValue(), movimientoId);
        Path archivo = datos.resolve(ruta);
        Files.createDirectories(archivo.getParent());
        Files.write(archivo, jpeg);
        return ruta;
    }

    /** El archivo de una ruta guardada en la base. Vacío si no existe o si apunta fuera de la carpeta de tickets. */
    public Optional<Path> archivo(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            return Optional.empty();
        }
        Path archivo = datos.resolve(ruta).normalize();
        if (!archivo.startsWith(tickets) || !Files.isRegularFile(archivo)) {
            return Optional.empty();
        }
        return Optional.of(archivo);
    }

    /** Borra la foto si existe. Un error se registra y no corta nada: es solo un archivo de más en disco. */
    public void borrar(String ruta) {
        archivo(ruta).ifPresent(archivo -> {
            try {
                Files.deleteIfExists(archivo);
            } catch (IOException e) {
                log.warn("No se pudo borrar la foto del ticket {}: {}", ruta, e.getMessage());
            }
        });
    }
}
