package ar.gastito.bot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Al arrancar dice, en la consola y por Telegram, con qué base está corriendo: así no se carga un gasto real en la
 * base de prueba sin darse cuenta. El nombre sale de la conexión, no del perfil.
 */
@Component
@ConditionalOnProperty(prefix = "telegrambots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AvisoArranque {

    private static final Logger log = LoggerFactory.getLogger(AvisoArranque.class);

    static final String BASE_REAL = "gastito";
    static final String BASE_DE_PRUEBA = "gastito_prueba";

    private final DataSource dataSource;
    private final GastitoBot bot;

    public AvisoArranque(DataSource dataSource, GastitoBot bot) {
        this.dataSource = dataSource;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        String url;
        try (Connection conexion = dataSource.getConnection()) {
            url = conexion.getMetaData().getURL();
        } catch (SQLException e) {
            log.error("No pude averiguar con qué base arrancó Gastito", e);
            return;
        }
        log.info("Gastito corriendo con la base {}{}", nombreBase(url), esPostgres(url) ? "" : " (H2 local)");
        if (!bot.enviarAlUsuario(List.of(Accion.Enviar.texto(mensaje(url))))) {
            log.warn("No se pudo mandar por Telegram el aviso de arranque");
        }
    }

    /** El nombre de la base en una URL de JDBC, sin parámetros ({@code ?…} en PostgreSQL, {@code ;…} en H2). */
    static String nombreBase(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceFirst("[?;].*$", "").replaceFirst("^.*[/:]", "");
    }

    private static boolean esPostgres(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    /** La base real es solo la {@value #BASE_REAL} de PostgreSQL: la H2 del perfil dev se llama igual. */
    static String mensaje(String url) {
        String base = nombreBase(url);
        if (!esPostgres(url)) {
            return "⚠️ Gastito arrancó con la base local H2 (" + base + "), que no es la real.";
        }
        return switch (base) {
            case BASE_REAL -> "🟢 Gastito arrancó con la base <b>real</b> (" + BASE_REAL + ").";
            case BASE_DE_PRUEBA -> "🧪 Gastito arrancó con la base de <b>PRUEBA</b> (" + BASE_DE_PRUEBA
                    + "): lo que cargues no va a la base real.";
            default -> "⚠️ Gastito arrancó con la base <b>" + base
                    + "</b>, que no es la real: lo que cargues no va a la base real.";
        };
    }
}
