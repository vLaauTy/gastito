package ar.gastito.recibo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ar.gastito.config.GastitoProperties;

/**
 * Los recibos en disco: carpetaDatos/recibos/AAAA/MM/<id del movimiento>.pdf (o .jpg). En la base queda la ruta
 * relativa a la carpeta de datos. Los archivos no salen de esta PC.
 */
@Component
public class ArchivosRecibo {

    private static final Logger log = LoggerFactory.getLogger(ArchivosRecibo.class);
    static final String CARPETA = "recibos";

    private final Path datos;
    private final Path recibos;

    @Autowired
    public ArchivosRecibo(GastitoProperties properties) {
        this(Path.of(properties.ticket().carpetaDatos()));
    }

    public ArchivosRecibo(Path carpetaDatos) {
        this.datos = carpetaDatos.toAbsolutePath().normalize();
        this.recibos = datos.resolve(CARPETA);
    }

    /**
     * @param extension "pdf" o "jpg"
     * @return la ruta relativa a la carpeta de datos, con barras "/"
     */
    public String guardar(long movimientoId, YearMonth periodo, String extension, byte[] contenido)
            throws IOException {
        String ruta = "%s/%04d/%02d/%d.%s".formatted(CARPETA, periodo.getYear(), periodo.getMonthValue(),
                movimientoId, extension);
        Path archivo = datos.resolve(ruta);
        Files.createDirectories(archivo.getParent());
        Files.write(archivo, contenido);
        return ruta;
    }

    /** El archivo de una ruta guardada. Vacío si no existe o si apunta fuera de la carpeta de recibos. */
    public Optional<Path> archivo(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            return Optional.empty();
        }
        Path archivo = datos.resolve(ruta).normalize();
        if (!archivo.startsWith(recibos) || !Files.isRegularFile(archivo)) {
            return Optional.empty();
        }
        return Optional.of(archivo);
    }

    /** Borra el archivo si existe. Un error se registra y no corta nada. */
    public void borrar(String ruta) {
        archivo(ruta).ifPresent(archivo -> {
            try {
                Files.deleteIfExists(archivo);
            } catch (IOException e) {
                log.warn("No se pudo borrar el recibo {}: {}", ruta, e.getMessage());
            }
        });
    }
}
